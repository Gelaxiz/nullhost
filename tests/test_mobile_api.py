import unittest
import os
import time
import json
import io
import hmac
import hashlib
import secrets
from app import app, NULLHOST_MOBILE_APP_KEY, verify_mobile_app_token

class MobileApiTestCase(unittest.TestCase):
    def setUp(self):
        app.config['TESTING'] = True
        app.config['WTF_CSRF_ENABLED'] = False
        self.client = app.test_client()

    def test_mobile_auth_handshake_success(self):
        client_name = 'symbian-belle-fp2'
        timestamp = int(time.time())
        nonce = f'testnonce_{secrets.token_hex(6)}'
        sig = hmac.new(
            NULLHOST_MOBILE_APP_KEY.encode('utf-8'),
            f"{client_name}:{timestamp}:{nonce}".encode('utf-8'),
            hashlib.sha256
        ).hexdigest()

        response = self.client.post('/api/mobile/auth', json={
            'client': client_name,
            'version': '1.0.0',
            'timestamp': timestamp,
            'nonce': nonce,
            'signature': sig
        })
        self.assertEqual(response.status_code, 200)
        data = response.get_json()
        self.assertEqual(data.get('status'), 'ok')
        self.assertIn('token', data)
        self.assertIn('server_time', data)
        self.assertTrue(verify_mobile_app_token(data['token']))

    def test_mobile_auth_handshake_invalid(self):
        response = self.client.post('/api/mobile/auth', json={
            'client': 'symbian-belle-fp2',
            'version': '1.0.0',
            'timestamp': int(time.time()),
            'nonce': f'invalid_{secrets.token_hex(6)}',
            'signature': 'invalidsignature123'
        })
        self.assertEqual(response.status_code, 401)

    def test_mobile_upload_with_token(self):
        # 1. Get token
        client_name = 'symbian-s60-keypad'
        timestamp = int(time.time())
        nonce = f'nonce_upload_{secrets.token_hex(6)}'
        sig = hmac.new(
            NULLHOST_MOBILE_APP_KEY.encode('utf-8'),
            f"{client_name}:{timestamp}:{nonce}".encode('utf-8'),
            hashlib.sha256
        ).hexdigest()

        auth_resp = self.client.post('/api/mobile/auth', json={
            'client': client_name,
            'version': '1.0.0',
            'timestamp': timestamp,
            'nonce': nonce,
            'signature': sig
        })
        token = auth_resp.get_json()['token']

        # 2. Upload file using token
        test_file = io.BytesIO(b'Test mobile upload content 2026')
        upload_resp = self.client.post('/upload', data={
            'file': (test_file, 'mobile_test.txt'),
            'app_token': token,
            'format': 'json',
            'expiry': '3600'
        }, headers={'X-Nullhost-App-Token': token})

        self.assertEqual(upload_resp.status_code, 200)
        result = upload_resp.get_json()
        self.assertTrue(result.get('success'))
        self.assertIn('id', result)
        self.assertIn('url', result)
        self.assertIn('raw_url', result)
        self.assertIn('qr_url', result)

    def test_mobile_items_and_delete(self):
        # Public items endpoint returns 200 list
        resp = self.client.get('/api/mobile/public')
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.get_json().get('status'), 'ok')

    def test_mobile_auth_security_hardenings(self):
        # 1. Plaintext secret is rejected
        resp = self.client.post('/api/mobile/auth', json={
            'client': 'symbian-belle-fp2',
            'version': '1.0.0',
            'timestamp': int(time.time()),
            'nonce': 'sec_nonce_1',
            'signature': NULLHOST_MOBILE_APP_KEY
        })
        self.assertEqual(resp.status_code, 401)

        # 2. Expired timestamp (e.g. 0) is rejected
        sig_zero = hmac.new(
            NULLHOST_MOBILE_APP_KEY.encode('utf-8'),
            b'symbian-belle-fp2:0:sec_nonce_zero',
            hashlib.sha256
        ).hexdigest()
        resp_zero = self.client.post('/api/mobile/auth', json={
            'client': 'symbian-belle-fp2',
            'version': '1.0.0',
            'timestamp': 0,
            'nonce': 'sec_nonce_zero',
            'signature': sig_zero
        })
        self.assertEqual(resp_zero.status_code, 401)

        # 3. Nonce replay is rejected
        ts = int(time.time())
        nonce_val = f'replay_nonce_{ts}'
        sig_replay = hmac.new(
            NULLHOST_MOBILE_APP_KEY.encode('utf-8'),
            f'symbian-belle-fp2:{ts}:{nonce_val}'.encode('utf-8'),
            hashlib.sha256
        ).hexdigest()
        ok_resp = self.client.post('/api/mobile/auth', json={
            'client': 'symbian-belle-fp2',
            'version': '1.0.0',
            'timestamp': ts,
            'nonce': nonce_val,
            'signature': sig_replay
        })
        self.assertEqual(ok_resp.status_code, 200)

        # Replay the exact same nonce within timestamp window
        replay_resp = self.client.post('/api/mobile/auth', json={
            'client': 'symbian-belle-fp2',
            'version': '1.0.0',
            'timestamp': ts,
            'nonce': nonce_val,
            'signature': sig_replay
        })
        self.assertEqual(replay_resp.status_code, 401)

    def _get_mobile_token(self):
        client_name = 'symbian-belle-fp2'
        timestamp = int(time.time())
        nonce = f'token_nonce_{secrets.token_hex(4)}'
        sig = hmac.new(
            NULLHOST_MOBILE_APP_KEY.encode('utf-8'),
            f"{client_name}:{timestamp}:{nonce}".encode('utf-8'),
            hashlib.sha256
        ).hexdigest()
        auth_resp = self.client.post('/api/mobile/auth', json={
            'client': client_name,
            'version': '1.0.0',
            'timestamp': timestamp,
            'nonce': nonce,
            'signature': sig
        })
        return auth_resp.get_json()['token']

    def test_anonymous_delete_token_enforcement(self):
        token = self._get_mobile_token()
        # 1. Upload anonymous paste using verified mobile app token
        resp = self.client.post('/upload', data={
            'paste_content': 'Sensitive content',
            'app_token': token,
            'format': 'json',
            'expiry': '3600'
        }, headers={'X-Nullhost-App-Token': token})
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        item_id = data['id']
        del_token = data.get('delete_token')
        self.assertTrue(del_token)

        # 2. Unauthenticated mobile delete attempt without delete_token must fail 403
        anon_client = app.test_client() # fresh client without session
        fail_del = anon_client.post('/api/mobile/delete', json={'id': item_id})
        self.assertEqual(fail_del.status_code, 403)

        # 3. Mobile delete with wrong token must fail 403
        wrong_del = anon_client.post('/api/mobile/delete', json={'id': item_id, 'delete_token': 'wrongtoken123'})
        self.assertEqual(wrong_del.status_code, 403)

        # 4. Mobile delete with correct token succeeds 200
        ok_del = anon_client.post('/api/mobile/delete', json={'id': item_id, 'delete_token': del_token})
        self.assertEqual(ok_del.status_code, 200)

    def test_private_lookup_does_not_leak_by_filename(self):
        token = self._get_mobile_token()
        # Upload private file with unique filename
        unique_fname = f'private_doc_{int(time.time())}.txt'
        resp = self.client.post('/upload', data={
            'file': (io.BytesIO(b'Private confidential document'), unique_fname),
            'app_token': token,
            'format': 'json',
            'expiry': '3600'
        }, headers={'X-Nullhost-App-Token': token})
        self.assertEqual(resp.status_code, 200)
        item_id = resp.get_json()['id']

        # Lookup by exact ID succeeds
        id_lookup = self.client.get(f'/api/mobile/item/{item_id}')
        self.assertEqual(id_lookup.status_code, 200)

        # Lookup by filename fails (404)
        fname_lookup = self.client.get(f'/api/mobile/item/{unique_fname}')
        self.assertEqual(fname_lookup.status_code, 404)

    def test_mobile_image_preview_returns_415_for_non_image(self):
        token = self._get_mobile_token()
        # Test 1: Paste item
        resp_paste = self.client.post('/upload', data={
            'paste_content': 'Hello text world',
            'app_token': token,
            'format': 'json',
            'expiry': '3600'
        }, headers={'X-Nullhost-App-Token': token})
        paste_id = resp_paste.get_json()['id']
        prev_paste = self.client.get(f'/api/mobile/preview/{paste_id}')
        self.assertEqual(prev_paste.status_code, 415)

        # Test 2: Uploaded non-image file on disk (e.g. .txt or .pdf)
        resp_file = self.client.post('/upload', data={
            'file': (io.BytesIO(b'Non-image document file content'), 'document.pdf'),
            'app_token': token,
            'format': 'json',
            'expiry': '3600'
        }, headers={'X-Nullhost-App-Token': token})
        file_id = resp_file.get_json()['id']
        prev_file = self.client.get(f'/api/mobile/preview/{file_id}')
        self.assertEqual(prev_file.status_code, 415)

if __name__ == '__main__':
    unittest.main()
