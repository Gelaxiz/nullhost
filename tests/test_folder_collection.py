import io
import os
import sqlite3
import tempfile
import time
import unittest
from unittest.mock import patch

os.environ['NULLHOST_DISABLE_CLEANUP_WORKER'] = '1'

import app as nullhost
import database


class FolderAndCollectionTests(unittest.TestCase):
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

    def test_init_batch_collection_creates_collection(self):
        with self.client.session_transaction() as session:
            session['verified_upload_batch'] = {
                'id': 'batch-123',
                'verified_at': int(time.time()),
                'uses_left': 10,
            }

        response = self.client.post('/collections/init_batch', data={
            'name': 'My Vacation Photos',
            'batch_id': 'batch-123',
            'password': 'secretpassword',
        })
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertIn('id', data)
        self.assertEqual(data['name'], 'My Vacation Photos')
        self.assertEqual(data['url'], f"/c/{data['id']}")
        self.assertEqual(data['download_zip_url'], f"/c/{data['id']}/download.zip")

        # Verify collection exists in DB
        with database.get_db() as conn:
            row = conn.execute('SELECT * FROM collections WHERE id = ?', (data['id'],)).fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row['name'], 'My Vacation Photos')
            self.assertIsNotNone(row['password_hash'])

    def test_upload_init_with_collection_attaches_immediately(self):
        # 1. Create collection
        with self.client.session_transaction() as session:
            session['verified_upload_batch'] = {
                'id': 'batch-folder',
                'verified_at': int(time.time()),
                'uses_left': 10,
            }

        col_res = self.client.post('/collections/init_batch', data={
            'name': 'ProjectFolder',
            'batch_id': 'batch-folder',
        })
        self.assertEqual(col_res.status_code, 200)
        col_id = col_res.get_json()['id']

        # 2. Upload init for file 1 inside subfolder "src"
        init_res = self.client.post('/upload_init', data={
            'batch_id': 'batch-folder',
            'upload_id': 'batch-folder_0',
            'total_chunks': '1',
            'filename': 'main.py',
            'mimetype': 'text/x-python',
            'collection_id': col_id,
            'collection_folder': 'src',
        })
        self.assertEqual(init_res.status_code, 200)
        init_data = init_res.get_json()
        file_id = init_data['id']
        self.assertEqual(init_data['collection_id'], col_id)

        # 3. Verify file is in collection IMMEDIATELY (even before chunks)
        with database.get_db() as conn:
            ci = conn.execute(
                'SELECT * FROM collection_items WHERE collection_id = ? AND item_id = ?',
                (col_id, file_id)
            ).fetchone()
            self.assertIsNotNone(ci)
            self.assertEqual(ci['folder_path'], 'src')

            item = conn.execute('SELECT * FROM items WHERE id = ?', (file_id,)).fetchone()
            self.assertEqual(item['is_partial'], 1)

        # 4. Collection view renders the item with partial status
        view_res = self.client.get(f'/c/{col_id}')
        self.assertEqual(view_res.status_code, 200)
        view_html = view_res.get_data(as_text=True)
        self.assertIn('main.py', view_html)
        self.assertIn('badge-partial', view_html)
        self.assertIn('btn-copy-item', view_html)
        self.assertIn(f'/v/{file_id}', view_html)

        # 5. Complete chunk upload
        chunk_res = self.client.post('/upload_chunk', data={
            'upload_id': 'batch-folder_0',
            'chunk_index': '0',
            'total_chunks': '1',
            'filename': 'main.py',
            'mimetype': 'text/x-python',
            'batch_id': 'batch-folder',
            'file': (io.BytesIO(b'print("hello")'), 'main.py'),
        })
        self.assertEqual(chunk_res.status_code, 200)

        # 6. Verify item is no longer partial
        with database.get_db() as conn:
            item = conn.execute('SELECT * FROM items WHERE id = ?', (file_id,)).fetchone()
            self.assertEqual(item['is_partial'], 0)

        # 7. Collection view now shows finished item without partial tag
        view_res2 = self.client.get(f'/c/{col_id}')
        view_html2 = view_res2.get_data(as_text=True)
        self.assertIn('main.py', view_html2)
        self.assertIn('btn-copy-item', view_html2)

    def test_collection_zip_download_get(self):
        # Create collection & upload a file
        with self.client.session_transaction() as session:
            session['verified_upload_batch'] = {
                'id': 'batch-zip',
                'verified_at': int(time.time()),
                'uses_left': 10,
            }

        col_res = self.client.post('/collections/init_batch', data={
            'name': 'ZipFolder',
            'batch_id': 'batch-zip',
        })
        col_id = col_res.get_json()['id']

        # Add chunk
        self.client.post('/upload_init', data={
            'batch_id': 'batch-zip',
            'upload_id': 'batch-zip_0',
            'total_chunks': '1',
            'filename': 'test.txt',
            'mimetype': 'text/plain',
            'collection_id': col_id,
        })
        self.client.post('/upload_chunk', data={
            'upload_id': 'batch-zip_0',
            'chunk_index': '0',
            'total_chunks': '1',
            'filename': 'test.txt',
            'mimetype': 'text/plain',
            'batch_id': 'batch-zip',
            'file': (io.BytesIO(b'file content'), 'test.txt'),
        })

        # Test GET /c/<col_id>/download.zip
        zip_res = self.client.get(f'/c/{col_id}/download.zip')
        self.assertEqual(zip_res.status_code, 200)
        self.assertEqual(zip_res.mimetype, 'application/zip')

    def test_multiple_files_batch_verification_and_collision_prevention(self):
        # 1. Initialize batch with 999 uses
        with self.client.session_transaction() as session:
            session['verified_upload_batch'] = {
                'id': 'batch-multi-123',
                'verified_at': int(time.time()),
                'uses_left': 999,
            }

        col_res = self.client.post('/collections/init_batch', data={
            'name': 'MultiBatch',
            'batch_id': 'batch-multi-123',
        })
        self.assertEqual(col_res.status_code, 200)
        col_id = col_res.get_json()['id']

        # 2. Upload file 1 with a custom_id in the request form
        f1_res = self.client.post('/upload_init', data={
            'batch_id': 'batch-multi-123',
            'upload_id': 'batch-multi-123_0',
            'total_chunks': '1',
            'filename': 'file1.txt',
            'collection_id': col_id,
            'custom_id': 'SAME_ID',
        })
        self.assertEqual(f1_res.status_code, 200)
        f1_id = f1_res.get_json()['id']
        # custom_id must be ignored for collection items to avoid collision
        self.assertNotEqual(f1_id, 'SAME_ID')

        # 3. Upload file 2 with the same custom_id in request form - must succeed without 400 or 403
        f2_res = self.client.post('/upload_init', data={
            'batch_id': 'batch-multi-123',
            'upload_id': 'batch-multi-123_1',
            'total_chunks': '1',
            'filename': 'file2.txt',
            'collection_id': col_id,
            'custom_id': 'SAME_ID',
        })
        self.assertEqual(f2_res.status_code, 200)
        f2_id = f2_res.get_json()['id']
        self.assertNotEqual(f2_id, 'SAME_ID')
        self.assertNotEqual(f1_id, f2_id)

    def test_init_batch_collection_with_custom_id_and_filename(self):
        with self.client.session_transaction() as session:
            session['verified_upload_batch'] = {
                'id': 'batch-custom-1',
                'verified_at': int(time.time()),
                'uses_left': 10,
            }

        res = self.client.post('/collections/init_batch', data={
            'name': 'Holiday Photos 2026',
            'custom_id': 'holiday-2026',
            'batch_id': 'batch-custom-1',
        })
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertEqual(data['id'], 'holiday-2026')
        self.assertEqual(data['name'], 'Holiday Photos 2026')
        self.assertEqual(data['url'], '/c/holiday-2026')

        # Overview route redirect
        v_res = self.client.get('/v/holiday-2026')
        self.assertEqual(v_res.status_code, 302)
        self.assertIn('/c/holiday-2026', v_res.headers['Location'])

        # Search route redirect
        s_res = self.client.get('/search?id=holiday-2026')
        self.assertEqual(s_res.status_code, 302)
        self.assertIn('/c/holiday-2026', s_res.headers['Location'])

    def test_delete_collection_requires_only_DELETE(self):
        with self.client.session_transaction() as session:
            session['account_id'] = 'acc-delete-test'

        with database.get_db() as conn:
            conn.execute('INSERT INTO accounts (account_id, created_at) VALUES (?, ?)', ('acc-delete-test', int(time.time())))
            conn.execute(
                'INSERT INTO collections (id, account_id, name, created_at) VALUES (?, ?, ?, ?)',
                ('col-to-del', 'acc-delete-test', 'Test Delete', int(time.time()))
            )
            conn.execute(
                'INSERT INTO items (id, type, account_id, filename, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)',
                ('item-del-1', 'file', 'acc-delete-test', 'del.txt', int(time.time()) + 3600, int(time.time()))
            )
            conn.execute(
                'INSERT INTO collection_items (collection_id, item_id, added_at) VALUES (?, ?, ?)',
                ('col-to-del', 'item-del-1', int(time.time()))
            )
            conn.commit()

        # Reject incorrect phrase
        bad_res = self.client.post('/collections/col-to-del/delete', data={
            'delete_contents': '1',
            'contents_confirmation': 'WRONG_TEXT',
        })
        self.assertEqual(bad_res.status_code, 400)

        # Accept exact 'DELETE'
        good_res = self.client.post('/collections/col-to-del/delete', data={
            'delete_contents': '1',
            'contents_confirmation': 'DELETE',
        })
        self.assertEqual(good_res.status_code, 302)

        # Verify collection and items were deleted
        with database.get_db() as conn:
            self.assertIsNone(conn.execute('SELECT 1 FROM collections WHERE id = ?', ('col-to-del',)).fetchone())
            self.assertIsNone(conn.execute('SELECT 1 FROM items WHERE id = ?', ('item-del-1',)).fetchone())


if __name__ == '__main__':
    unittest.main()

