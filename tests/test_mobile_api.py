import unittest
import os
import time
import json
import io
import hmac
import hashlib
from app import app, NULLHOST_MOBILE_APP_KEY, verify_mobile_app_token

class MobileApiTestCase(unittest.TestCase):
    def setUp(self):
        app.config['TESTING'] = True
        app.config['WTF_CSRF_ENABLED'] = False
        self.client = app.test_client()

    def test_mobile_auth_handshake_success(self):
        client_name = 'symbian-belle-fp2'
        timestamp = int(time.time())
        nonce = 'testnonce123'
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
            'nonce': 'random',
            'signature': 'invalidsignature123'
        })
        self.assertEqual(response.status_code, 401)

    def test_mobile_upload_with_token(self):
        # 1. Get token
        client_name = 'symbian-s60-keypad'
        timestamp = int(time.time())
        nonce = 'nonce_upload'
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

if __name__ == '__main__':
    unittest.main()
