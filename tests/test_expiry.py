import io
import os
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

os.environ['NULLHOST_DISABLE_CLEANUP_WORKER'] = '1'

import app as nullhost
import database


class ExpiryTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.old_db_path = database.DB_PATH
        self.old_upload_folder = nullhost.app.config['UPLOAD_FOLDER']
        database.DB_PATH = os.path.join(self.temp_dir.name, 'test.db')
        nullhost.app.config['UPLOAD_FOLDER'] = self.temp_dir.name
        database.init_db()
        nullhost.app.config.update(TESTING=True)
        self.client = nullhost.app.test_client()

    def tearDown(self):
        database.DB_PATH = self.old_db_path
        nullhost.app.config['UPLOAD_FOLDER'] = self.old_upload_folder
        self.temp_dir.cleanup()

    def upload_paste(self, expiry, dynamic_expiry=False):
        with self.client.session_transaction() as session:
            session['verified_upload_batch'] = {
                'id': 'expiry-tests',
                'verified_at': 1_000_000,
                'uses_left': 10,
            }
        data = {
            'batch_id': 'expiry-tests',
            'paste_content': 'expiry test',
            'expiry': str(expiry),
            'ajax': '1',
        }
        if dynamic_expiry:
            data['dynamic_expiry'] = 'on'
        response = self.client.post('/upload', data=data)
        self.assertEqual(response.status_code, 200, response.get_data(as_text=True))
        payload = response.get_json()
        item_id = payload.get('id') or payload['info_url'].rstrip('/').split('/')[-1]
        self.assertEqual(payload['download_url'], f'/v/{item_id}/paste.txt/download')
        with database.get_db() as conn:
            item = conn.execute('SELECT * FROM items WHERE id = ?', (item_id,)).fetchone()
        return item_id, dict(item)

    def test_selected_one_hour_and_one_day_are_stored_exactly(self):
        with patch('app.time.time', return_value=1_000_000):
            hour_id, hour = self.upload_paste(3600)
            day_id, day = self.upload_paste(86400)
        self.assertEqual(hour['expires_at'], 1_003_600)
        self.assertEqual(day['expires_at'], 1_086_400)
        self.assertNotEqual(hour_id, day_id)

    def test_info_and_raw_views_never_extend_expiration(self):
        with patch('app.time.time', return_value=1_000_000):
            item_id, original = self.upload_paste(3600)
            self.assertEqual(self.client.get(f'/v/{item_id}').status_code, 200)
            self.assertEqual(self.client.get(f'/raw/{item_id}').status_code, 200)
            self.assertEqual(self.client.get(f'/raw/{item_id}?dl=1').status_code, 200)
        with database.get_db() as conn:
            current = conn.execute('SELECT expires_at, views FROM items WHERE id = ?', (item_id,)).fetchone()
        self.assertEqual(current['expires_at'], original['expires_at'])
        self.assertEqual(current['views'], 2)

    def test_item_is_unavailable_at_exact_expiration_time(self):
        with patch('app.time.time', return_value=1_000_000):
            item_id, item = self.upload_paste(3600)
        with patch('app.time.time', return_value=item['expires_at']):
            self.assertEqual(self.client.get(f'/v/{item_id}').status_code, 404)
            self.assertEqual(self.client.get(f'/raw/{item_id}').status_code, 404)

    def test_account_creation_is_deferred_until_turnstile_verifies(self):
        page = self.client.get('/a').get_data(as_text=True)
        self.assertIn('data-deferred-turnstile', page)
        self.assertIn('data-account-turnstile-gate hidden', page)
        self.assertIn('event.preventDefault()', page)
        self.assertIn('form.requestSubmit(submitButton)', page)
        self.assertIn('data-action="turnstile-spin-v2"', page)

        denied = self.client.post('/a', data={'action': 'create'})
        self.assertEqual(denied.status_code, 403)
        with database.get_db() as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM accounts').fetchone()[0], 0)

        with patch('app.verify_turnstile', return_value=True):
            created = self.client.post('/a', data={
                'action': 'create',
                'cf-turnstile-response': 'verified-test-token',
            })
        self.assertEqual(created.status_code, 302)
        with database.get_db() as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM accounts').fetchone()[0], 1)

    def test_account_nuke_requires_only_exact_nuke_phrase(self):
        account_id = '1234567890123456'
        with database.get_db() as conn:
            conn.execute('INSERT INTO accounts (account_id, created_at) VALUES (?, ?)', (account_id, 1_000_000))
            conn.commit()
        with self.client.session_transaction() as session:
            session['account_id'] = account_id

        page = self.client.get('/a').get_data(as_text=True)
        self.assertIn('type NUKE to confirm', page)
        self.assertIn('pattern="NUKE"', page)
        self.assertNotIn('confirm_final', page)

        rejected = self.client.post('/account/nuke', data={'confirmation': f'NUKE {account_id}'})
        self.assertEqual(rejected.status_code, 400)
        with database.get_db() as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM accounts WHERE account_id = ?', (account_id,)).fetchone()[0], 1)

        deleted = self.client.post('/account/nuke', data={'confirmation': 'NUKE'})
        self.assertEqual(deleted.status_code, 302)
        with database.get_db() as conn:
            self.assertEqual(conn.execute('SELECT COUNT(*) FROM accounts WHERE account_id = ?', (account_id,)).fetchone()[0], 0)

    def test_raw_action_is_visible_for_binary_file_types(self):
        now = 1_000_000
        with database.get_db() as conn:
            conn.execute('''
                INSERT INTO items (
                    id, type, filename, burn_after_read, is_public, expires_at,
                    dynamic_expiry, views, size, mimetype, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', ('binaryitem', 'file', 'archive.zip', 0, 0, now + 3600, 0, 0, 128, 'application/zip', now))
            conn.commit()
        with patch('app.time.time', return_value=now):
            response = self.client.get('/v/binaryitem')
        self.assertEqual(response.status_code, 200)
        html = response.get_data(as_text=True)
        self.assertIn('href="/v/binaryitem/archive.zip"', html)
        self.assertIn('view raw', html)
        self.assertLess(html.index('view raw'), html.index('share &amp; open on another device'))
        self.assertIn('class="item-action-row"', html)
        self.assertNotIn('class="quick-download-link"', html)
        self.assertIn('id="download-url-input"', html)
        self.assertIn('formats your browser cannot preview download automatically', html)
        self.assertIn('/v/binaryitem/archive.zip/download', html)

    def test_image_share_menu_has_copyable_download_url(self):
        now = 1_000_000
        with database.get_db() as conn:
            conn.execute('''
                INSERT INTO items (
                    id, type, filename, burn_after_read, is_public, expires_at,
                    dynamic_expiry, views, size, mimetype, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', ('imageitem', 'file', 'photo.jpg', 0, 0, now + 3600, 0, 0, 128, 'image/jpeg', now))
            conn.commit()
        with patch('app.time.time', return_value=now):
            response = self.client.get('/v/imageitem', base_url='https://nullhost.gelaxiz.xyz')
        html = response.get_data(as_text=True)
        self.assertIn('id="download-url-input"', html)
        self.assertIn('value="https://nullhost.gelaxiz.xyz/v/imageitem/photo.jpg/download"', html)
        with open(os.path.join(self.temp_dir.name, 'imageitem_photo.jpg'), 'wb') as image_file:
            image_file.write(b'fake jpeg data')
        with patch('app.time.time', return_value=now):
            download = self.client.get('/v/imageitem/photo.jpg/download')
        self.assertEqual(download.status_code, 200)
        self.assertIn('attachment', download.headers['Content-Disposition'])
        self.assertIn('photo.jpg', download.headers['Content-Disposition'])

    def test_dynamic_expiry_is_opt_in_and_only_raw_views_extend_it(self):
        with patch('app.time.time', return_value=1_000_000):
            item_id, original = self.upload_paste(3600, dynamic_expiry=True)
            self.assertEqual(original['dynamic_expiry'], 1)
            self.assertEqual(self.client.get(f'/v/{item_id}').status_code, 200)
            with database.get_db() as conn:
                after_info = conn.execute('SELECT expires_at FROM items WHERE id = ?', (item_id,)).fetchone()
            self.assertEqual(after_info['expires_at'], original['expires_at'])
            self.assertEqual(self.client.get(f'/raw/{item_id}').status_code, 200)
        with database.get_db() as conn:
            after_raw = conn.execute('SELECT expires_at FROM items WHERE id = ?', (item_id,)).fetchone()
        self.assertEqual(after_raw['expires_at'], original['expires_at'] + (3 * 86400))

    def test_dynamic_expiry_stops_at_180_days_and_never_shortens_longer_expiry(self):
        with patch('app.time.time', return_value=1_000_000):
            capped_id, capped = self.upload_paste(3600, dynamic_expiry=True)
            with database.get_db() as conn:
                conn.execute(
                    'UPDATE items SET expires_at = ? WHERE id = ?',
                    (capped['created_at'] + (180 * 86400) - 3600, capped_id),
                )
                conn.commit()
            self.assertEqual(self.client.get(f'/raw/{capped_id}').status_code, 200)
            long_id, long_item = self.upload_paste(3600, dynamic_expiry=True)
            long_expiry = long_item['created_at'] + (365 * 86400)
            with database.get_db() as conn:
                conn.execute('UPDATE items SET expires_at = ? WHERE id = ?', (long_expiry, long_id))
                conn.commit()
            self.assertEqual(self.client.get(f'/raw/{long_id}').status_code, 200)
        with database.get_db() as conn:
            capped_after = conn.execute('SELECT expires_at FROM items WHERE id = ?', (capped_id,)).fetchone()
            long_after = conn.execute('SELECT expires_at FROM items WHERE id = ?', (long_id,)).fetchone()
        self.assertEqual(capped_after['expires_at'], capped['created_at'] + (180 * 86400))
        self.assertEqual(long_after['expires_at'], long_expiry)


class ChunkUploadTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.old_db_path = database.DB_PATH
        self.old_upload_folder = nullhost.app.config['UPLOAD_FOLDER']
        database.DB_PATH = os.path.join(self.temp_dir.name, 'test.db')
        nullhost.app.config['UPLOAD_FOLDER'] = self.temp_dir.name
        database.init_db()
        nullhost.app.config.update(TESTING=True)
        self.client = nullhost.app.test_client()

    def tearDown(self):
        database.DB_PATH = self.old_db_path
        nullhost.app.config['UPLOAD_FOLDER'] = self.old_upload_folder
        self.temp_dir.cleanup()

    def _setup_verified_batch(self, batch_id='test-batch'):
        with self.client.session_transaction() as session:
            session['verified_upload_batch'] = {
                'id': batch_id,
                'verified_at': 1_000_000,
                'uses_left': 10,
            }

    def test_upload_init_creates_item_and_returns_immediate_links(self):
        with patch('app.time.time', return_value=1_000_000):
            self._setup_verified_batch('batch-init-1')
            response = self.client.post('/upload_init', data={
                'batch_id': 'batch-init-1',
                'upload_id': 'up_init_1',
                'total_chunks': '3',
                'filename': 'video.mp4',
                'mimetype': 'video/mp4',
                'expiry': '86400',
            })
            self.assertEqual(response.status_code, 200)
            data = response.get_json()
            self.assertEqual(data['status'], 'ready')
            item_id = data['id']
            self.assertEqual(data['url'], f'/v/{item_id}')
            self.assertEqual(data['raw_url'], f'/raw/{item_id}/video.mp4')
            self.assertEqual(data['download_url'], f'/v/{item_id}/video.mp4/download')

            # Verify in DB
            with database.get_db() as conn:
                item = conn.execute('SELECT * FROM items WHERE id = ?', (item_id,)).fetchone()
            self.assertIsNotNone(item)
            self.assertEqual(item['filename'], 'video.mp4')
            self.assertEqual(item['size'], 0)

            # Verify file exists on disk
            filepath = os.path.join(self.temp_dir.name, f'{item_id}_video.mp4')
            self.assertTrue(os.path.exists(filepath))
            self.assertEqual(os.path.getsize(filepath), 0)

    def test_partial_upload_preserves_broken_stuff_when_stopped(self):
        chunk1 = b'PARTIAL_CHUNK_ONE_' * 100
        chunk2 = b'PARTIAL_CHUNK_TWO_' * 100

        with patch('app.time.time', return_value=1_000_000):
            self._setup_verified_batch('batch-partial-1')
            # Step 1: Init upload
            init_res = self.client.post('/upload_init', data={
                'batch_id': 'batch-partial-1',
                'upload_id': 'up_partial_test',
                'total_chunks': '3',
                'filename': 'archive.zip',
                'expiry': '86400',
            })
            self.assertEqual(init_res.status_code, 200)
            item_id = init_res.get_json()['id']

            # Step 2: Upload chunk 0
            c0_res = self.client.post('/upload_chunk', data={
                'upload_id': 'up_partial_test',
                'chunk_index': '0',
                'total_chunks': '3',
                'file': (io.BytesIO(chunk1), 'archive.zip'),
            })
            self.assertEqual(c0_res.status_code, 200)

            # Step 3: Upload chunk 1
            c1_res = self.client.post('/upload_chunk', data={
                'upload_id': 'up_partial_test',
                'chunk_index': '1',
                'total_chunks': '3',
                'file': (io.BytesIO(chunk2), 'archive.zip'),
            })
            self.assertEqual(c1_res.status_code, 200)

            # Note: Upload STOPS HERE (chunk 2 is never sent, simulating user cancel or network interruption)

            # Check database item has partial size
            with database.get_db() as conn:
                item = conn.execute('SELECT * FROM items WHERE id = ?', (item_id,)).fetchone()
            self.assertIsNotNone(item)
            expected_size = len(chunk1) + len(chunk2)
            self.assertEqual(item['size'], expected_size)
            self.assertEqual(item['is_partial'], 1)

            # Check that overview page loads with partial warnings
            view_res = self.client.get(f'/v/{item_id}')
            self.assertEqual(view_res.status_code, 200)
            self.assertIn('PARTIAL UPLOAD / INCOMPLETE FILE', view_res.get_data(as_text=True))
            self.assertIn('partial (incomplete / broken)', view_res.get_data(as_text=True))

            # Check that raw link serves partial content ("broken stuff") with X-Nullhost-Partial header
            raw_res = self.client.get(f'/raw/{item_id}/archive.zip')
            self.assertEqual(raw_res.status_code, 200)
            self.assertEqual(raw_res.data, chunk1 + chunk2)
            self.assertEqual(raw_res.headers.get('X-Nullhost-Partial'), '1')

            # Check that download serves partial content
            dl_res = self.client.get(f'/v/{item_id}/archive.zip/download')
            self.assertEqual(dl_res.status_code, 200)
            self.assertEqual(dl_res.data, chunk1 + chunk2)
            self.assertEqual(dl_res.headers.get('X-Nullhost-Partial'), '1')

            # Step 4: Now send the final chunk (chunk 2) to complete the upload
            chunk3 = b'FINAL_CHUNK_THREE_' * 50
            c2_res = self.client.post('/upload_chunk', data={
                'upload_id': 'up_partial_test',
                'chunk_index': '2',
                'total_chunks': '3',
                'file': (io.BytesIO(chunk3), 'archive.zip'),
            })
            self.assertEqual(c2_res.status_code, 200)

            # Verify that is_partial is now 0 (completed)
            with database.get_db() as conn:
                item_done = conn.execute('SELECT * FROM items WHERE id = ?', (item_id,)).fetchone()
            self.assertEqual(item_done['is_partial'], 0)
            self.assertEqual(item_done['size'], expected_size + len(chunk3))

            # Check that overview page no longer displays the partial warning
            view_done_res = self.client.get(f'/v/{item_id}')
            self.assertNotIn('PARTIAL UPLOAD / INCOMPLETE FILE', view_done_res.get_data(as_text=True))

            # Check that raw endpoint no longer sends X-Nullhost-Partial header
            raw_done_res = self.client.get(f'/raw/{item_id}/archive.zip')
            self.assertNotIn('X-Nullhost-Partial', raw_done_res.headers)

    def test_direct_chunk_upload_without_prior_init(self):
        chunk0 = b'DIRECT_CHUNK_0_CONTENT'

        with patch('app.time.time', return_value=1_000_000):
            self._setup_verified_batch('batch-direct-1')
            res = self.client.post('/upload_chunk', data={
                'batch_id': 'batch-direct-1',
                'upload_id': 'up_direct_1',
                'chunk_index': '0',
                'total_chunks': '2',
                'filename': 'doc.pdf',
                'file': (io.BytesIO(chunk0), 'doc.pdf'),
                'expiry': '86400',
            })
            self.assertEqual(res.status_code, 200)
            data = res.get_json()
            self.assertEqual(data['status'], 'chunk_received')
            item_id = data['id']

            # Verify chunk 0 was saved directly and registered in DB with is_partial = 1
            with database.get_db() as conn:
                item = conn.execute('SELECT * FROM items WHERE id = ?', (item_id,)).fetchone()
            self.assertIsNotNone(item)
            self.assertEqual(item['size'], len(chunk0))
            self.assertEqual(item['filename'], 'doc.pdf')
            self.assertEqual(item['is_partial'], 1)

            # Upload final chunk (1 of 2)
            chunk1 = b'DIRECT_CHUNK_1_CONTENT'
            res2 = self.client.post('/upload_chunk', data={
                'upload_id': 'up_direct_1',
                'chunk_index': '1',
                'total_chunks': '2',
                'file': (io.BytesIO(chunk1), 'doc.pdf'),
            })
            self.assertEqual(res2.status_code, 200)
            with database.get_db() as conn:
                item2 = conn.execute('SELECT * FROM items WHERE id = ?', (item_id,)).fetchone()
            self.assertEqual(item2['is_partial'], 0)
            self.assertEqual(item2['size'], len(chunk0) + len(chunk1))

    def _upload_file(self, filename='file.txt', content=b'hello'):
        self._setup_verified_batch('batch-upload')
        res = self.client.post('/upload', data={
            'batch_id': 'batch-upload',
            'file': (io.BytesIO(content), filename),
            'expiry': '86400',
            'ajax': '1',
        })
        self.assertEqual(res.status_code, 200)
        return res.get_json()['id']

    def test_anonymous_auto_collection_creation(self):
        with patch('app.time.time', return_value=1_000_000):
            # Upload two files anonymously
            id1 = self._upload_file('photo1.jpg', b'JPEG_1')
            id2 = self._upload_file('photo2.jpg', b'JPEG_2')

            # Request automatic collection creation without prior account login
            res = self.client.post('/collections/auto', json={
                'item_ids': [id1, id2]
            })
            self.assertEqual(res.status_code, 200, res.get_data(as_text=True))
            payload = res.get_json()
            self.assertIn('url', payload)
            self.assertIn('id', payload)
            collection_id = payload['id']
            self.assertEqual(payload['url'], f'/c/{collection_id}')

            # Verify collection exists in DB and items are attached
            with database.get_db() as conn:
                col = conn.execute('SELECT * FROM collections WHERE id = ?', (collection_id,)).fetchone()
                self.assertIsNotNone(col)
                items = conn.execute('SELECT * FROM collection_items WHERE collection_id = ?', (collection_id,)).fetchall()
                self.assertEqual(len(items), 2)

            # Verify collection view renders successfully
            col_res = self.client.get(f'/c/{collection_id}')
            self.assertEqual(col_res.status_code, 200)

    def test_code_hero_card_on_overview(self):
        with patch('app.time.time', return_value=1_000_000):
            item_id = self._upload_file('sample.pdf', b'PDF_CONTENT')
            res = self.client.get(f'/v/{item_id}')
            self.assertEqual(res.status_code, 200)
            html = res.get_data(as_text=True)
            self.assertIn('code-hero-card', html)
            self.assertIn('overview-item-code', html)
            self.assertIn('btn-copy-code', html)
            self.assertIn(item_id, html)


if __name__ == '__main__':
    unittest.main()
