import os
import time
import uuid
import random
import secrets
import threading
import io
import base64
import tempfile
import zipfile
import json
import urllib.parse
import urllib.request
import hashlib
import hmac
from datetime import timedelta
import pyotp
import qrcode
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.utils import secure_filename
from flask import Flask, request, render_template, redirect, url_for, send_from_directory, make_response, session, abort, send_file, after_this_request, jsonify
from PIL import Image

from database import get_db, init_db

LOCAL_ENV_FILE = os.path.join(os.path.dirname(__file__), '.env')
if os.path.exists(LOCAL_ENV_FILE):
    with open(LOCAL_ENV_FILE, 'r', encoding='utf-8') as env_file:
        for env_line in env_file:
            env_line = env_line.strip()
            if not env_line or env_line.startswith('#'):
                continue
            env_key, separator, env_value = env_line.partition('=')
            if separator:
                os.environ.setdefault(env_key.strip(), env_value.strip())

app = Flask(__name__)

SECRET_KEY_FILE = os.path.join(os.path.dirname(__file__), '.secret_key')
if os.path.exists(SECRET_KEY_FILE):
    with open(SECRET_KEY_FILE, 'rb') as f:
        app.secret_key = f.read()
else:
    app.secret_key = os.urandom(24)
    with open(SECRET_KEY_FILE, 'wb') as f:
        f.write(app.secret_key)
app.config['UPLOAD_FOLDER'] = os.path.join(os.path.dirname(__file__), 'uploads')
app.config['MAX_CONTENT_LENGTH'] = 10 * 1024 * 1024 * 1024  # 10 GB max
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=365)
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'
app.config['SESSION_COOKIE_SECURE'] = True

TURNSTILE_SITE_KEY = os.environ.get('TURNSTILE_SITE_KEY', '')
TURNSTILE_SECRET = os.environ.get('TURNSTILE_SECRET', '')
ENABLE_TURNSTILE = os.environ.get('ENABLE_TURNSTILE', '').lower() not in ('0', 'false', 'no') and bool(TURNSTILE_SECRET and TURNSTILE_SITE_KEY)
TURNSTILE_ACTION = os.environ.get('TURNSTILE_ACTION', 'turnstile-spin-v2')
TURNSTILE_VERIFY_URL = 'https://challenges.cloudflare.com/turnstile/v0/siteverify'
NULLHOST_CLIENT_VERSION = '1.1.3'
NULLHOST_MOBILE_APP_KEY = os.environ.get('NULLHOST_MOBILE_APP_KEY', 'nullhost_secret_mobile_app_key_2026')

app.jinja_env.globals['turnstile_enabled'] = ENABLE_TURNSTILE
app.jinja_env.globals['turnstile_site_key'] = TURNSTILE_SITE_KEY
app.jinja_env.globals['turnstile_action'] = TURNSTILE_ACTION

LEGACY_BROWSER_MARKERS = (
    'nokiabrowser', 'nokia', 'symbian', 'series40', 'series60',
    's40ovibrowser', 'midp', 'cldc', 'opera mini', 'operamini',
    'belle', 'meego', 'maemo', 'symbos',
)

def is_legacy_browser():
    if request.path.startswith('/lite'):
        return True
    referer = request.headers.get('Referer', '').lower()
    if '/lite' in referer:
        return True
    override = request.args.get('lite')
    if override == '1':
        return True
    if override == '0':
        return False
    user_agent = request.headers.get('User-Agent', '').lower()
    if 'android' in user_agent:
        return False
    return any(marker in user_agent for marker in LEGACY_BROWSER_MARKERS)

@app.context_processor
def browser_mode_context():
    return {'legacy_mode': is_legacy_browser()}

@app.before_request
def make_session_permanent():
    session.permanent = True
    if request.method in ('POST', 'PUT', 'PATCH', 'DELETE'):
        source = request.headers.get('Origin') or request.headers.get('Referer')
        if source:
            parsed = urllib.parse.urlparse(source)
            if parsed.netloc != request.host:
                abort(403, description='Cross-origin request rejected.')

@app.after_request
def prevent_uploaded_content_indexing(response):
    uploaded_paths = ('/uploads/', '/v/', '/raw/', '/c/')
    if request.path.startswith(uploaded_paths):
        response.headers['X-Robots-Tag'] = 'noindex, nofollow, noarchive'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'DENY'
    response.headers['Referrer-Policy'] = 'strict-origin-when-cross-origin'
    response.headers['Permissions-Policy'] = 'camera=(), microphone=(), geolocation=()'
    response.headers['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains'
    response.headers['Content-Security-Policy'] = "frame-ancestors 'none'; base-uri 'self'; object-src 'none'"
    return response

# Ensure upload directory and db exist
os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)
init_db()

for sensitive_file in (SECRET_KEY_FILE, LOCAL_ENV_FILE):
    if os.path.exists(sensitive_file):
        os.chmod(sensitive_file, 0o600)

def get_admin_totp_secret():
    return os.environ.get('ADMIN_TOTP_SECRET', '').strip()

def verify_admin_totp(code):
    if not code:
        return False
    secret = get_admin_totp_secret()
    if not secret:
        return False
    totp = pyotp.TOTP(secret)
    return totp.verify(str(code).strip(), valid_window=1)

# Rate limiting dictionary for admin login
admin_login_attempts = {}
desktop_upload_attempts = {}

def authenticate_desktop_client():
    account_id = request.headers.get('X-Nullhost-Account', '').strip() or request.form.get('account_id', '').strip()
    if not account_id or not account_id.isdigit() or len(account_id) not in (16, 24):
        return None
    with get_db() as conn:
        exists = conn.execute('SELECT 1 FROM accounts WHERE account_id = ?', (account_id,)).fetchone()
    return account_id if exists else None

def verify_mobile_app_token(token_str):
    if not token_str:
        return False
    parts = str(token_str).strip().rsplit(':', 1)
    if len(parts) != 2:
        return False
    payload, sig = parts
    expected = hmac.new(NULLHOST_MOBILE_APP_KEY.encode('utf-8'), payload.encode('utf-8'), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(sig, expected):
        return False
    try:
        client, created_at, nonce = payload.split(':')
        if abs(int(time.time()) - int(created_at)) > 7200:
            return False
        return True
    except Exception:
        return False

def authenticate_mobile_client():
    token = request.headers.get('X-Nullhost-App-Token', '').strip() or request.form.get('app_token', '').strip()
    if verify_mobile_app_token(token):
        return True
    user_agent = request.headers.get('User-Agent', '').lower()
    if 'nullhost-j2me' in user_agent or 'nullhost-symbian' in user_agent:
        return True
    if is_legacy_browser():
        return True
    return False

def desktop_upload_allowed(account_id):
    if not account_id:
        return False
    ip = get_client_ip()
    now = time.time()
    recent = [stamp for stamp in desktop_upload_attempts.get(ip, []) if now - stamp < 3600]
    if len(recent) >= 20:
        desktop_upload_attempts[ip] = recent
        return False
    recent.append(now)
    desktop_upload_attempts[ip] = recent
    return True

def generate_id(length=8, format_type='both'):
    if format_type == 'numbers':
        chars = '0123456789'
    elif format_type == 'letters':
        chars = 'abcdefghijklmnopqrstuvwxyz'
    else:
        chars = 'abcdefghijklmnopqrstuvwxyz0123456789'
    return ''.join(secrets.choice(chars) for _ in range(length))

def generate_account_id():
    return ''.join(secrets.choice('0123456789') for _ in range(24))

def sanitize_folder_path(raw_path):
    parts = []
    for part in (raw_path or '').replace('\\', '/').split('/'):
        cleaned = secure_filename(part.strip())
        if cleaned and cleaned not in ('.', '..'):
            parts.append(cleaned)
    return '/'.join(parts[:8])[:240]

def attach_item_to_collection(conn, collection_id, item_id, account_id, folder_path=''):
    if not collection_id or not account_id:
        return False
    collection = conn.execute(
        'SELECT id FROM collections WHERE id = ? AND account_id = ?',
        (collection_id, account_id)
    ).fetchone()
    if not collection:
        return False
    conn.execute('''
        INSERT INTO collection_items (collection_id, item_id, folder_path, added_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(collection_id, item_id) DO UPDATE SET folder_path = excluded.folder_path
    ''', (collection_id, item_id, sanitize_folder_path(folder_path), int(time.time())))
    return True

def remove_physical_item_file(item):
    if item['type'] != 'file' or not item['filename']:
        return
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
    try:
        os.remove(filepath)
    except FileNotFoundError:
        pass

def get_client_ip():
    return (
        request.headers.get('CF-Connecting-IP')
        or request.headers.get('X-Forwarded-For', '').split(',', 1)[0].strip()
        or request.remote_addr
        or ''
    )

def verify_turnstile():
    if not ENABLE_TURNSTILE:
        return True
    secret = os.environ.get('TURNSTILE_SECRET')
    token = request.form.get('cf-turnstile-response', '').strip()
    if not secret or not token:
        return False

    body = urllib.parse.urlencode({
        'secret': secret,
        'response': token,
        'remoteip': get_client_ip(),
    }).encode('utf-8')
    verification_request = urllib.request.Request(
        TURNSTILE_VERIFY_URL,
        data=body,
        headers={'Content-Type': 'application/x-www-form-urlencoded'},
        method='POST',
    )
    try:
        with urllib.request.urlopen(verification_request, timeout=8) as response:
            if response.status != 200:
                return False
            result = json.loads(response.read().decode('utf-8'))
    except (OSError, ValueError):
        return False
    return result.get('success') is True and result.get('action') == TURNSTILE_ACTION

def build_collection_tree(items):
    root = {'folders': {}, 'items': []}
    for item in items:
        node = root
        for part in filter(None, item.get('folder_path', '').split('/')):
            node = node['folders'].setdefault(part, {'folders': {}, 'items': []})
        node['items'].append(item)
    return root

def get_display_type(item):
    if item['type'] == 'file' and item['filename']:
        exts = ('.zip', '.tar', '.gz', '.7z', '.rar', '.bz2', '.xz')
        if item['filename'].lower().endswith(exts):
            return 'archive'
    return item['type']

def scrub_exif_data(filepath):
    try:
        with Image.open(filepath) as img:
            # simply saving without passing exif strips it in pillow
            # handle modes properly to avoid errors with transparency
            if img.mode in ('RGBA', 'LA'):
                background = Image.new(img.mode[:-1], img.size, (255, 255, 255))
                background.paste(img, img.split()[-1])
                img = background
            img.save(filepath, format=img.format or 'JPEG')
    except Exception:
        pass

def cleanup_worker():
    while True:
        with get_db() as conn:
            cursor = conn.cursor()
            now = int(time.time())
            cursor.execute("SELECT id, type, filename FROM items WHERE expires_at <= ?", (now,))
            expired_items = cursor.fetchall()
            for item in expired_items:
                if item['type'] == 'file' and item['filename']:
                    filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
                    if os.path.exists(filepath):
                        try:
                            os.remove(filepath)
                        except Exception:
                            pass
                cursor.execute("DELETE FROM items WHERE id = ?", (item['id'],))
            cursor.execute("DELETE FROM tombstones WHERE expires_at <= ?", (now,))
            conn.commit()
        time.sleep(600) # Check every 10 minutes

if os.environ.get('NULLHOST_DISABLE_CLEANUP_WORKER') != '1':
    threading.Thread(target=cleanup_worker, daemon=True).start()

@app.route('/')
@app.route('/lite')
def index():
    account_id = session.get('account_id')
    collections = []
    if account_id:
        with get_db() as conn:
            collections = [dict(row) for row in conn.execute(
                'SELECT id, name FROM collections WHERE account_id = ? ORDER BY created_at DESC',
                (account_id,)
            ).fetchall()]
    template = 'index_legacy.html' if is_legacy_browser() else 'index.html'
    return render_template(template, logged_in=bool(account_id), collections=collections)

@app.route('/robots.txt')
def robots_txt():
    return send_from_directory(app.static_folder, 'robots.txt', mimetype='text/plain')

@app.route('/favicon.ico')
def favicon():
    icon_path = os.path.join(os.path.dirname(__file__), 'client', 'logo.ico')
    return send_file(icon_path, mimetype='image/x-icon', max_age=86400)

@app.route('/app')
def app_page():
    return render_template('app.html')

@app.route('/download-app')
@app.route('/download-app/<path:filename>')
def download_app(filename=None):
    target = filename or request.args.get('platform', 'windows')
    client_dir = os.path.join(os.path.dirname(__file__), 'client')
    
    file_map = {
        'windows': ('Nullhost.exe', 'application/x-msdos-program'),
        'symbian': ('Nullhost.jar', 'application/java-archive'),
        'belle': ('Nullhost.jar', 'application/java-archive'),
        's60': ('Nullhost.jar', 'application/java-archive'),
        'mobile': ('Nullhost.jar', 'application/java-archive'),
        'j2me': ('Nullhost.jar', 'application/java-archive'),
        'jar': ('Nullhost.jar', 'application/java-archive'),
        'jad': ('Nullhost.jad', 'text/vnd.sun.j2me.app-descriptor'),
    }
    
    if target in file_map:
        fname, mtype = file_map[target]
    else:
        fname = secure_filename(target)
        if fname.endswith(('.sisx', '.sis')):
            mtype = 'application/vnd.symbian.install'
        elif fname.endswith('.jar'):
            mtype = 'application/java-archive'
        elif fname.endswith('.jad'):
            mtype = 'text/vnd.sun.j2me.app-descriptor'
        elif fname.endswith('.exe'):
            mtype = 'application/x-msdos-program'
        elif fname.endswith('.zip'):
            mtype = 'application/zip'
        else:
            mtype = 'application/octet-stream'
        
    fpath = os.path.join(client_dir, fname)
    if os.path.exists(fpath):
        resp = make_response(send_file(fpath, as_attachment=True, download_name=fname, mimetype=mtype))
        resp.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate, max-age=0'
        resp.headers['Pragma'] = 'no-cache'
        resp.headers['Expires'] = '0'
        return resp
        
    return redirect(f"https://github.com/Gelaxiz/nullhost/releases/latest/download/{fname}")

@app.route('/api/mobile/auth', methods=['POST'])
def mobile_auth():
    data = request.get_json(silent=True) or request.form
    client = (data.get('client') or '').strip()
    client_version = (data.get('version') or '1.0').strip()
    timestamp = data.get('timestamp')
    nonce = (data.get('nonce') or '').strip()
    signature = (data.get('signature') or '').strip()
    
    valid_clients = ('symbian-belle-fp2', 'symbian-s60-keypad', 'j2me-universal', 'windows-desktop')
    if client not in valid_clients:
        return jsonify({'status': 'error', 'message': 'Unknown client platform'}), 400
    
    expected_sig = hmac.new(
        NULLHOST_MOBILE_APP_KEY.encode('utf-8'),
        f"{client}:{timestamp}:{nonce}".encode('utf-8'),
        hashlib.sha256
    ).hexdigest()
    
    is_valid = hmac.compare_digest(signature, expected_sig) or (signature == NULLHOST_MOBILE_APP_KEY)
    if not is_valid:
        return jsonify({'status': 'error', 'message': 'Invalid app credentials'}), 401
    
    now = int(time.time())
    token_payload = f"{client}:{now}:{secrets.token_hex(8)}"
    token_sig = hmac.new(NULLHOST_MOBILE_APP_KEY.encode('utf-8'), token_payload.encode('utf-8'), hashlib.sha256).hexdigest()
    upload_token = f"{token_payload}:{token_sig}"
    
    return jsonify({
        'status': 'ok',
        'token': upload_token,
        'server_time': now,
        'expires_in': 7200
    })

@app.route('/api/mobile/upload', methods=['POST'])
def mobile_upload():
    return upload()

@app.route('/api/mobile/items')
def mobile_items():
    account_id = request.headers.get('X-Nullhost-Account', '').strip() or request.args.get('account_id', '').strip()
    if not account_id:
        return jsonify({'status': 'ok', 'items': []})
    with get_db() as conn:
        rows = conn.execute(
            'SELECT id, type, filename, size, created_at, expires_at, views, password_hash FROM items WHERE account_id = ? ORDER BY created_at DESC LIMIT 50',
            (account_id,)
        ).fetchall()
    items = []
    for r in rows:
        fname = r['filename'] or ('paste.txt' if r['type'] == 'paste' else 'file')
        items.append({
            'id': r['id'],
            'filename': fname,
            'size': r['size'] or 0,
            'type': r['type'],
            'views': r['views'] or 0,
            'created_at': r['created_at'],
            'expires_at': r['expires_at'],
            'has_password': bool(r['password_hash']),
            'url': url_for('overview', item_id=r['id'], _external=True),
            'raw_url': url_for('raw', item_id=r['id'], filename=fname, _external=True),
            'download_url': url_for('download', item_id=r['id'], filename=fname, _external=True),
            'qr_url': url_for('item_qr', item_id=r['id'], _external=True) + '?size=5',
            'thumb_url': url_for('raw', item_id=r['id'], filename=fname, _external=True) if fname.lower().endswith(('.jpg', '.jpeg', '.png', '.gif')) else ''
        })
    return jsonify({'status': 'ok', 'items': items})

@app.route('/api/mobile/public')
def mobile_public():
    now = int(time.time())
    with get_db() as conn:
        rows = conn.execute(
            'SELECT id, type, filename, size, created_at, expires_at FROM items WHERE is_public = 1 AND expires_at > ? ORDER BY created_at DESC LIMIT 30',
            (now,)
        ).fetchall()
    items = []
    for r in rows:
        fname = r['filename'] or ('paste.txt' if r['type'] == 'paste' else 'file')
        items.append({
            'id': r['id'],
            'filename': fname,
            'size': r['size'] or 0,
            'type': r['type'],
            'created_at': r['created_at'],
            'expires_at': r['expires_at'],
            'url': url_for('overview', item_id=r['id'], _external=True),
            'raw_url': url_for('raw', item_id=r['id'], filename=fname, _external=True),
            'download_url': url_for('download', item_id=r['id'], filename=fname, _external=True),
            'qr_url': url_for('item_qr', item_id=r['id'], _external=True) + '?size=5'
        })
    return jsonify({'status': 'ok', 'items': items})

@app.route('/api/mobile/delete', methods=['POST'])
def mobile_delete():
    data = request.get_json(silent=True) or request.form
    item_id = (data.get('id') or '').strip()
    account_id = request.headers.get('X-Nullhost-Account', '').strip() or (data.get('account_id') or '').strip()
    if not item_id:
        return jsonify({'status': 'error', 'message': 'Missing item ID'}), 400
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,))
        item = cur.fetchone()
        if not item:
            return jsonify({'status': 'error', 'message': 'Item not found'}), 404
        if item['account_id'] and item['account_id'] != account_id:
            return jsonify({'status': 'error', 'message': 'Unauthorized'}), 403
        remove_physical_item_file(item)
        conn.execute("DELETE FROM items WHERE id = ?", (item_id,))
        conn.commit()
    return jsonify({'status': 'ok', 'message': 'Item deleted'})

@app.route('/api/mobile/item/<item_id>')
def mobile_item_lookup(item_id):
    item_id = (item_id or '').strip()
    if not item_id:
        return jsonify({'status': 'error', 'message': 'Missing item ID'}), 400
    now = int(time.time())
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM items WHERE id = ? COLLATE NOCASE OR filename = ? COLLATE NOCASE", (item_id, item_id))
        item = cur.fetchone()
        if not item:
            col = conn.execute("SELECT * FROM collections WHERE id = ? COLLATE NOCASE", (item_id,)).fetchone()
            if col:
                return jsonify({
                    'status': 'ok',
                    'id': col['id'],
                    'filename': (col['name'] or col['id']) + '.zip',
                    'size': 0,
                    'type': 'collection',
                    'views': 0,
                    'expires_at': 9999999999,
                    'has_password': bool(col['password_hash']),
                    'burn_after_read': 0,
                    'content': None,
                    'url': url_for('collection_view', collection_id=col['id'], _external=True),
                    'raw_url': url_for('collection_zip', collection_id=col['id'], _external=True),
                    'download_url': url_for('collection_zip', collection_id=col['id'], _external=True),
                    'qr_url': url_for('collection_qr', collection_id=col['id'], _external=True) + '?size=5'
                })
            return jsonify({'status': 'error', 'message': 'Item not found'}), 404
        if item['expires_at'] <= now:
            return jsonify({'status': 'error', 'message': 'Item has expired'}), 410
        
        pwd = (request.args.get('password') or request.headers.get('X-Nullhost-Password') or '').strip()
        has_pwd = bool(item['password_hash'])
        pwd_ok = False
        if has_pwd:
            if pwd and check_password_hash(item['password_hash'], pwd):
                pwd_ok = True
                session[f'auth_{item["id"]}'] = True
        else:
            pwd_ok = True

        fname = item['filename'] or ('paste.txt' if item['type'] == 'paste' else 'file')
        content_val = item['content'] if (item['type'] == 'paste' and pwd_ok) else None

        raw_url = url_for('raw', item_id=item['id'], filename=fname, _external=True)
        dl_url = url_for('download', item_id=item['id'], filename=fname, _external=True)
        if has_pwd and pwd_ok and pwd:
            import urllib.parse
            raw_url += '?password=' + urllib.parse.quote_plus(pwd)
            dl_url += '?password=' + urllib.parse.quote_plus(pwd)

        return jsonify({
            'status': 'ok',
            'id': item['id'],
            'filename': fname,
            'size': item['size'] or 0,
            'type': item['type'],
            'views': item['views'] or 0,
            'expires_at': item['expires_at'],
            'has_password': has_pwd,
            'password_ok': pwd_ok,
            'burn_after_read': item['burn_after_read'] or 0,
            'content': content_val,
            'url': url_for('overview', item_id=item['id'], _external=True),
            'raw_url': raw_url,
            'download_url': dl_url,
            'qr_url': url_for('item_qr', item_id=item['id'], _external=True) + '?size=5'
        })

@app.route('/api/client/update')
def client_update_manifest():
    client_path = os.path.join(os.path.dirname(__file__), 'client', 'Nullhost.exe')
    digest = hashlib.sha256()
    with open(client_path, 'rb') as client_file:
        for chunk in iter(lambda: client_file.read(1024 * 1024), b''):
            digest.update(chunk)
    response = jsonify({
        'version': NULLHOST_CLIENT_VERSION,
        'download_url': url_for('download_app', _external=True, _scheme='https'),
        'sha256': digest.hexdigest(),
    })
    response.headers['Cache-Control'] = 'no-store'
    return response

@app.route('/tos')
def tos():
    return render_template('tos.html')

@app.route('/privacy')
def privacy():
    return render_template('privacy.html')

@app.route('/search')
def search():
    item_id = request.args.get('id', '').strip()
    if item_id in ('!admin', '(LolGelaxiz_1BesT!)'):
        return redirect(url_for('admin_gate'))
    if item_id:
        with get_db() as conn:
            col = conn.execute('SELECT id FROM collections WHERE id = ?', (item_id,)).fetchone()
            if col:
                return redirect(url_for('collection_view', collection_id=item_id))
        return redirect(url_for('overview', item_id=item_id))
    return redirect(url_for('index'))

@app.route('/uploads/')
def public_uploads():
    now = int(time.time())
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM items WHERE is_public = 1 AND expires_at > ? ORDER BY created_at DESC", (now,))
        items_raw = cur.fetchall()
        items = []
        for row in items_raw:
            d = dict(row)
            d['display_type'] = get_display_type(d)
            items.append(d)
    template = 'public_legacy.html' if is_legacy_browser() else 'public.html'
    return render_template(template, items=items)

def calculate_expiry(expiry_raw, custom_days_raw, account_id, now):
    expiry_str = str(expiry_raw or '86400').strip().lower()
    if expiry_str in ('infinity', 'never'):
        expiry_val = 9999999999
    elif expiry_str == 'custom':
        try:
            custom_days = int(custom_days_raw or 1)
        except (ValueError, TypeError):
            custom_days = 1
        expiry_val = custom_days * 86400
    elif expiry_str.endswith('h'):
        try:
            expiry_val = int(expiry_str[:-1]) * 3600
        except (ValueError, TypeError):
            expiry_val = 86400
    elif expiry_str.endswith('d'):
        try:
            expiry_val = int(expiry_str[:-1]) * 86400
        except (ValueError, TypeError):
            expiry_val = 86400
    else:
        try:
            expiry_val = int(expiry_str)
        except (ValueError, TypeError):
            expiry_val = 86400

    # Retention over 24 hours (86400s) is strictly restricted to valid registered accounts
    valid_account = False
    if account_id:
        try:
            with get_db() as conn:
                acc = conn.execute("SELECT 1 FROM accounts WHERE account_id = ?", (account_id,)).fetchone()
                if acc:
                    valid_account = True
        except Exception:
            valid_account = False

    if not valid_account:
        if expiry_val > 86400:
            expiry_val = 86400
    else:
        if expiry_val != 9999999999 and expiry_val > 1825 * 86400:
            expiry_val = 1825 * 86400

    return now + expiry_val if expiry_val != 9999999999 else 9999999999

def get_or_generate_item_id(custom_id, url_format, now):
    custom_id = (custom_id or '').strip()
    if custom_id:
        clean_id = custom_id.replace('-', '').replace('_', '')
        if not clean_id or not clean_id.isalnum() or len(custom_id) > 32:
            return None, "error: custom link must be alphanumeric and max 32 chars"
        reserved_names = {'api', 'admin', 'static', 'uploads', 'c', 'v', 'raw', 'search', 'tos', 'privacy', 'dmca', 'about', 'a', 'app', 'download-app', 'gelaxiz_admin_gate'}
        if custom_id.lower() in reserved_names:
            return None, "error: custom link is reserved"
        with get_db() as conn:
            cur = conn.execute("SELECT id FROM items WHERE id = ? UNION SELECT id FROM tombstones WHERE id = ? AND expires_at > ? UNION SELECT id FROM collections WHERE id = ?", (custom_id, custom_id, now, custom_id))
            if cur.fetchone():
                return None, "error: custom link already exists or is temporarily quarantined after deletion"
        return custom_id, None

    fmt = url_format or 'both'
    item_id = generate_id(format_type=fmt)
    while True:
        with get_db() as conn:
            cur = conn.execute("SELECT id FROM items WHERE id = ? UNION SELECT id FROM tombstones WHERE id = ? AND expires_at > ? UNION SELECT id FROM collections WHERE id = ?", (item_id, item_id, now, item_id))
            if not cur.fetchone():
                break
        item_id = generate_id(format_type=fmt)
    return item_id, None

@app.route('/upload', methods=['POST'])
def upload():
    desktop_account = authenticate_desktop_client()
    is_verified_mobile = authenticate_mobile_client()
    if desktop_account:
        if not desktop_upload_allowed(desktop_account):
            return 'error: desktop upload rate limit reached. try again later', 429
        if request.content_length and request.content_length > 1024 * 1024 * 1024:
            return 'error: desktop uploads are limited to 1 GB', 413
        session['account_id'] = desktop_account
    elif is_verified_mobile:
        if desktop_account:
            session['account_id'] = desktop_account
    else:
        batch_id = secure_filename(request.form.get('batch_id', ''))
        batch = session.get('verified_upload_batch', {})
        batch_verified = (
            batch_id and batch.get('id') == batch_id
            and int(time.time()) - int(batch.get('verified_at', 0)) <= 3600
            and int(batch.get('uses_left', 0)) > 0
        )
        if batch_verified:
            batch['uses_left'] -= 1
            session['verified_upload_batch'] = batch
            session.modified = True
        elif not verify_turnstile():
            return 'error: verification failed. complete the verification and try again', 403
        elif batch_id:
            session['verified_upload_batch'] = {'id': batch_id, 'verified_at': int(time.time()), 'uses_left': 999}
            session.modified = True

    # Determine if logged in (session, desktop token, or verified mobile header/form)
    account_id = (session.get('account_id') or request.headers.get('X-Nullhost-Account', '').strip() or request.form.get('account_id', '').strip()) or None
    if account_id:
        try:
            with get_db() as conn:
                if not conn.execute("SELECT 1 FROM accounts WHERE account_id = ?", (account_id,)).fetchone():
                    account_id = None
        except Exception:
            account_id = None
    now = int(time.time())
    
    item_id, err = get_or_generate_item_id(request.form.get('custom_id'), request.form.get('url_format'), now)
    if err:
        return err, 400

    expires_at = calculate_expiry(
        request.form.get('expiry', '86400'),
        request.form.get('custom_expiry_days'),
        account_id,
        now
    )

    try:
        burn = int(request.form.get('burn_limit', 0))
    except (ValueError, TypeError):
        burn = 0
    dynamic_expiry = 1 if request.form.get('dynamic_expiry') == 'on' else 0
    password = request.form.get('password')
    p_hash = generate_password_hash(password) if password else None
    
    is_public = 0
    if account_id and request.form.get('is_public') == 'on':
        is_public = 1
    
    # File vs Paste
    if 'file' in request.files and request.files['file'].filename != '':
        file = request.files['file']
        
        custom_filename = request.form.get('custom_filename', '').strip()
        if custom_filename:
            filename = secure_filename(custom_filename)
        else:
            filename = secure_filename(file.filename)
            
        if not filename:
            filename = "file"
            
        filepath = os.path.join(app.config['UPLOAD_FOLDER'], item_id + '_' + filename)
        file.save(filepath)
        size = os.path.getsize(filepath)
        
        scrub = request.form.get('scrub_exif') == 'on'
        
        if scrub:
            scrub_exif_data(filepath)
            size = os.path.getsize(filepath)
        
        with get_db() as conn:
            conn.execute('''
                INSERT INTO items (id, type, account_id, filename, password_hash, burn_after_read, is_public, expires_at, dynamic_expiry, views, size, mimetype, created_at, is_partial)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ''', (item_id, 'file', account_id, filename, p_hash, burn, is_public, expires_at, dynamic_expiry, 0, size, file.mimetype, now))
            conn.commit()
            
    elif request.form.get('paste_content') or request.form.get('text'):
        content = request.form.get('paste_content') or request.form.get('text')
        size = len(content.encode('utf-8'))
        
        with get_db() as conn:
            conn.execute('''
                INSERT INTO items (id, type, account_id, content, password_hash, burn_after_read, is_public, expires_at, dynamic_expiry, views, size, mimetype, created_at, is_partial)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            ''', (item_id, 'paste', account_id, content, p_hash, burn, is_public, expires_at, dynamic_expiry, 0, size, 'text/plain', now))
            conn.commit()
    else:
        return "error: no file or content provided", 400

    collection_id = request.form.get('collection_id', '').strip()
    if account_id and collection_id:
        with get_db() as conn:
            attach_item_to_collection(
                conn,
                collection_id,
                item_id,
                account_id,
                request.form.get('collection_folder', '')
            )
            conn.commit()

    if not account_id:
        session.modified = True
        anon_uploads = session.get('anon_uploads', [])
        anon_uploads.append(item_id)
        session['anon_uploads'] = anon_uploads

    if request.form.get('ajax') == '1' or request.form.get('format') == 'json' or request.headers.get('Accept') == 'application/json' or is_verified_mobile:
        is_external = bool(is_verified_mobile or request.form.get('format') == 'json')
        if 'file' in request.files and request.files['file'].filename != '':
            raw_path = url_for('raw', item_id=item_id, filename=filename)
            dl_path = url_for('download', item_id=item_id, filename=filename)
            actual_fname = filename
        else:
            raw_path = url_for('raw', item_id=item_id)
            dl_path = url_for('download', item_id=item_id, filename='paste.txt')
            actual_fname = 'paste.txt'
        return jsonify({
            "success": True,
            "id": item_id,
            "url": url_for('overview', item_id=item_id, _external=is_external),
            "raw_url": url_for('raw', item_id=item_id, filename=actual_fname, _external=is_external) if 'file' in request.files else url_for('raw', item_id=item_id, _external=is_external),
            "download_url": dl_path if not is_external else url_for('download', item_id=item_id, filename=actual_fname, _external=True),
            "qr_url": (url_for('item_qr', item_id=item_id, _external=True) + '?size=5') if is_verified_mobile else url_for('item_qr', item_id=item_id, _external=True),
            "delete_url": url_for('delete_item', item_id=item_id, _external=True),
            "filename": actual_fname,
            "size": size if 'size' in locals() else None,
            "expires_at": expires_at
        })
    return redirect(url_for('overview', item_id=item_id))

@app.route('/upload_init', methods=['POST'])
def upload_init():
    desktop_account = authenticate_desktop_client()
    is_verified_mobile = authenticate_mobile_client()
    if desktop_account:
        session['account_id'] = desktop_account
        if not desktop_upload_allowed(desktop_account):
            return 'error: desktop upload rate limit reached. try again later', 429
    elif is_verified_mobile:
        if desktop_account:
            session['account_id'] = desktop_account
    else:
        batch_id = secure_filename(request.form.get('batch_id', ''))
        batch = session.get('verified_upload_batch', {})
        batch_verified = (
            batch_id and batch.get('id') == batch_id
            and int(time.time()) - int(batch.get('verified_at', 0)) <= 3600
            and int(batch.get('uses_left', 0)) > 0
        )
        if batch_verified:
            batch['uses_left'] -= 1
            session['verified_upload_batch'] = batch
            session.modified = True
        elif not verify_turnstile():
            return 'error: verification failed. complete the verification and try again', 403
        elif batch_id:
            session['verified_upload_batch'] = {'id': batch_id, 'verified_at': int(time.time()), 'uses_left': 999}
            session.modified = True

    upload_id = request.form.get('upload_id', '').strip()
    if not upload_id or not upload_id.replace('_', '').replace('-', '').isalnum():
        return "error: invalid upload_id", 400
    secure_id = secure_filename(upload_id)

    try:
        total_chunks = int(request.form.get('total_chunks', 1))
    except (ValueError, TypeError):
        return "error: invalid total chunks", 400
    if total_chunks < 1 or total_chunks > 10000:
        return "error: invalid chunk range", 400

    account_id = (session.get('account_id') or request.headers.get('X-Nullhost-Account', '').strip() or request.form.get('account_id', '').strip()) or None
    if account_id:
        try:
            with get_db() as conn:
                if not conn.execute("SELECT 1 FROM accounts WHERE account_id = ?", (account_id,)).fetchone():
                    account_id = None
        except Exception:
            account_id = None
    now = int(time.time())

    collection_id = request.form.get('collection_id', '').strip()
    custom_id = None if collection_id else request.form.get('custom_id')
    item_id, err = get_or_generate_item_id(custom_id, request.form.get('url_format'), now)
    if err:
        return err, 400

    expires_at = calculate_expiry(
        request.form.get('expiry', '86400'),
        request.form.get('custom_expiry_days'),
        account_id,
        now
    )

    try:
        burn = int(request.form.get('burn_limit', 0))
    except (ValueError, TypeError):
        burn = 0
    dynamic_expiry = 1 if request.form.get('dynamic_expiry') == 'on' else 0
    password = request.form.get('password')
    p_hash = generate_password_hash(password) if password else None
    is_public = 1 if (account_id and request.form.get('is_public') == 'on') else 0

    custom_filename = request.form.get('custom_filename', '').strip()
    if custom_filename:
        filename = secure_filename(custom_filename)
    else:
        orig_name = request.form.get('filename', '')
        filename = secure_filename(orig_name)
    if not filename:
        filename = "file"

    final_path = os.path.join(app.config['UPLOAD_FOLDER'], item_id + '_' + filename)
    with open(final_path, 'wb') as f:
        pass

    mimetype = request.form.get('mimetype', '') or 'application/octet-stream'

    with get_db() as conn:
        conn.execute('''
            INSERT INTO items (id, type, account_id, filename, password_hash, burn_after_read, is_public, expires_at, dynamic_expiry, views, size, mimetype, created_at, is_partial)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
        ''', (item_id, 'file', account_id, filename, p_hash, burn, is_public, expires_at, dynamic_expiry, 0, 0, mimetype, now))
        conn.commit()

    if not account_id:
        session.modified = True
        anon_uploads = session.get('anon_uploads', [])
        anon_uploads.append(item_id)
        session['anon_uploads'] = anon_uploads

    collection_id = request.form.get('collection_id', '').strip()
    if collection_id and account_id:
        with get_db() as conn:
            attach_item_to_collection(conn, collection_id, item_id, account_id, request.form.get('collection_folder', ''))
            conn.commit()

    session['turnstile_chunk_upload'] = {
        'id': secure_id,
        'verified_at': now,
        'item_id': item_id,
        'filename': filename,
        'final_path': final_path,
        'next_index': 0,
        'total_chunks': total_chunks,
        'collection_id': collection_id,
        'collection_folder': request.form.get('collection_folder', ''),
        'scrub_exif': request.form.get('scrub_exif') == 'on'
    }

    raw_url = url_for('raw', item_id=item_id, filename=filename)
    download_url = url_for('download', item_id=item_id, filename=filename)
    overview_url = url_for('overview', item_id=item_id)

    res = {
        "status": "ready",
        "id": item_id,
        "url": overview_url,
        "raw_url": raw_url,
        "download_url": download_url
    }
    if collection_id:
        res["collection_id"] = collection_id
        res["collection_url"] = url_for('collection_view', collection_id=collection_id)
    return res, 200

@app.route('/upload_chunk', methods=['POST'])
def upload_chunk():
    desktop_account = authenticate_desktop_client()
    if desktop_account:
        session['account_id'] = desktop_account
    upload_id = request.form.get('upload_id', '').strip()
    if not upload_id or not upload_id.replace('_', '').replace('-', '').isalnum():
        return "error: invalid upload_id", 400
    secure_id = secure_filename(upload_id)
    
    try:
        chunk_index = int(request.form.get('chunk_index', 0))
        total_chunks = int(request.form.get('total_chunks', 1))
    except (ValueError, TypeError):
        return "error: invalid chunk index or total", 400
        
    if chunk_index < 0 or total_chunks < 1 or total_chunks > 10000 or chunk_index >= total_chunks:
        return "error: invalid chunk range", 400
    if 'file' not in request.files:
        return "error: no file chunk provided", 400

    chunk_verification = session.get('turnstile_chunk_upload', {})
    now = int(time.time())
    chunk_is_verified = (
        chunk_verification.get('id') == secure_id
        and now - int(chunk_verification.get('verified_at', 0)) <= 7200
    )

    account_id = session.get('account_id')
    chunk_file = request.files['file']

    # Case A: chunk 0 and NOT pre-initialized by /upload_init
    if chunk_index == 0 and (not chunk_is_verified or 'final_path' not in chunk_verification):
        if desktop_account:
            if not desktop_upload_allowed(desktop_account):
                return 'error: desktop upload rate limit reached. try again later', 429
        else:
            batch_id = secure_filename(request.form.get('batch_id', ''))
            batch = session.get('verified_upload_batch', {})
            batch_verified = (
                batch_id and batch.get('id') == batch_id
                and now - int(batch.get('verified_at', 0)) <= 3600
                and int(batch.get('uses_left', 0)) > 0
            )
            if batch_verified:
                batch['uses_left'] -= 1
                session['verified_upload_batch'] = batch
                session.modified = True
            elif not verify_turnstile():
                return 'error: verification failed. complete the verification and try again', 403
            elif batch_id:
                session['verified_upload_batch'] = {'id': batch_id, 'verified_at': now, 'uses_left': 999}
                session.modified = True

        collection_id = request.form.get('collection_id', '').strip()
        custom_id = None if collection_id else request.form.get('custom_id')
        item_id, err = get_or_generate_item_id(custom_id, request.form.get('url_format'), now)
        if err:
            return err, 400

        expires_at = calculate_expiry(
            request.form.get('expiry', '86400'),
            request.form.get('custom_expiry_days'),
            account_id,
            now
        )
        try:
            burn = int(request.form.get('burn_limit', 0))
        except (ValueError, TypeError):
            burn = 0
        dynamic_expiry = 1 if request.form.get('dynamic_expiry') == 'on' else 0
        password = request.form.get('password')
        p_hash = generate_password_hash(password) if password else None
        is_public = 1 if (account_id and request.form.get('is_public') == 'on') else 0

        custom_filename = request.form.get('custom_filename', '').strip()
        if custom_filename:
            filename = secure_filename(custom_filename)
        else:
            orig_name = request.form.get('filename', '') or chunk_file.filename
            filename = secure_filename(orig_name)
        if not filename:
            filename = "file"

        final_path = os.path.join(app.config['UPLOAD_FOLDER'], item_id + '_' + filename)
        with open(final_path, 'wb') as f:
            f.write(chunk_file.read())
        initial_size = os.path.getsize(final_path)

        if initial_size > app.config['MAX_CONTENT_LENGTH']:
            if os.path.exists(final_path): os.remove(final_path)
            return "error: assembled upload exceeds the maximum size", 413

        mimetype = request.form.get('mimetype', '') or chunk_file.mimetype or 'application/octet-stream'

        is_partial = 0 if total_chunks == 1 else 1
        with get_db() as conn:
            conn.execute('''
                INSERT INTO items (id, type, account_id, filename, password_hash, burn_after_read, is_public, expires_at, dynamic_expiry, views, size, mimetype, created_at, is_partial)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', (item_id, 'file', account_id, filename, p_hash, burn, is_public, expires_at, dynamic_expiry, 0, initial_size, mimetype, now, is_partial))
            conn.commit()

        if not account_id:
            session.modified = True
            anon_uploads = session.get('anon_uploads', [])
            anon_uploads.append(item_id)
            session['anon_uploads'] = anon_uploads

        collection_id = request.form.get('collection_id', '').strip()

        chunk_verification = {
            'id': secure_id,
            'verified_at': now,
            'item_id': item_id,
            'filename': filename,
            'final_path': final_path,
            'next_index': 1,
            'total_chunks': total_chunks,
            'collection_id': collection_id,
            'collection_folder': request.form.get('collection_folder', ''),
            'scrub_exif': request.form.get('scrub_exif') == 'on'
        }
        session['turnstile_chunk_upload'] = chunk_verification

        raw_url = url_for('raw', item_id=item_id, filename=filename)
        download_url = url_for('download', item_id=item_id, filename=filename)
        view_url = url_for('overview', item_id=item_id)

        if total_chunks == 1:
            if chunk_verification.get('scrub_exif'):
                scrub_exif_data(final_path)
                final_size = os.path.getsize(final_path)
                with get_db() as conn:
                    conn.execute("UPDATE items SET size = ?, is_partial = 0 WHERE id = ?", (final_size, item_id))
                    conn.commit()
            else:
                with get_db() as conn:
                    conn.execute("UPDATE items SET is_partial = 0 WHERE id = ?", (item_id,))
                    conn.commit()
            if collection_id and account_id:
                with get_db() as conn:
                    attach_item_to_collection(conn, collection_id, item_id, account_id, chunk_verification.get('collection_folder', ''))
                    conn.commit()
            session.pop('turnstile_chunk_upload', None)
            session.modified = True
            return {"id": item_id, "url": view_url, "raw_url": raw_url, "download_url": download_url}, 200

        return {
            "status": "chunk_received",
            "index": 0,
            "id": item_id,
            "url": view_url,
            "raw_url": raw_url,
            "download_url": download_url
        }, 200

    # Case B: pre-initialized or chunk_index > 0
    if not chunk_is_verified:
        return 'error: upload verification expired. restart the upload', 403

    if chunk_verification.get('total_chunks') != total_chunks or chunk_verification.get('next_index') != chunk_index:
        return "error: chunks must be uploaded once and in order", 409

    final_path = chunk_verification.get('final_path')
    item_id = chunk_verification.get('item_id')
    filename = chunk_verification.get('filename')

    if not final_path or not item_id or not filename:
        return "error: invalid upload session state", 500

    with open(final_path, 'ab') as f:
        f.write(chunk_file.read())

    current_size = os.path.getsize(final_path)
    if current_size > app.config['MAX_CONTENT_LENGTH']:
        if os.path.exists(final_path): os.remove(final_path)
        with get_db() as conn:
            conn.execute("DELETE FROM items WHERE id = ?", (item_id,))
            conn.commit()
        session.pop('turnstile_chunk_upload', None)
        return "error: assembled upload exceeds the maximum size", 413

    with get_db() as conn:
        conn.execute("UPDATE items SET size = ? WHERE id = ?", (current_size, item_id))
        conn.commit()

    chunk_verification['next_index'] = chunk_index + 1
    session['turnstile_chunk_upload'] = chunk_verification

    raw_url = url_for('raw', item_id=item_id, filename=filename)
    download_url = url_for('download', item_id=item_id, filename=filename)
    view_url = url_for('overview', item_id=item_id)

    if chunk_index < total_chunks - 1:
        return {
            "status": "chunk_received",
            "index": chunk_index,
            "id": item_id,
            "url": view_url,
            "raw_url": raw_url,
            "download_url": download_url
        }, 200

    # Final chunk handling
    if chunk_verification.get('scrub_exif'):
        scrub_exif_data(final_path)
        final_size = os.path.getsize(final_path)
        with get_db() as conn:
            conn.execute("UPDATE items SET size = ?, is_partial = 0 WHERE id = ?", (final_size, item_id))
            conn.commit()
    else:
        with get_db() as conn:
            conn.execute("UPDATE items SET is_partial = 0 WHERE id = ?", (item_id,))
            conn.commit()

    collection_id = chunk_verification.get('collection_id')
    if account_id and collection_id:
        with get_db() as conn:
            attach_item_to_collection(conn, collection_id, item_id, account_id, chunk_verification.get('collection_folder', ''))
            conn.commit()

    session.pop('turnstile_chunk_upload', None)
    session.modified = True
    return {"id": item_id, "url": view_url, "raw_url": raw_url, "download_url": download_url}, 200

@app.route('/a', methods=['GET', 'POST'])
def account():
    if request.method == 'POST':
        action = request.form.get('action')
        desktop_account = authenticate_desktop_client()
        if action in ('create', 'login') and not desktop_account and not verify_turnstile():
            return 'error: verification failed. complete the verification and try again', 403
        if action == 'create':
            acc_id = generate_account_id()
            with get_db() as conn:
                conn.execute("INSERT INTO accounts (account_id, created_at) VALUES (?, ?)", (acc_id, int(time.time())))
                conn.commit()
            session['account_id'] = acc_id
            return redirect(url_for('account'))
        elif action == 'login':
            acc_id = request.form.get('account_id')
            with get_db() as conn:
                cur = conn.execute("SELECT * FROM accounts WHERE account_id = ?", (acc_id,))
                if cur.fetchone():
                    session['account_id'] = acc_id
                else:
                    return "error: invalid account number", 401
            return redirect(url_for('account'))
        elif action == 'logout':
            session.pop('account_id', None)
            return redirect(url_for('account'))

    if 'account_id' in session:
        acc_id = session['account_id']
        with get_db() as conn:
            cur = conn.execute("SELECT * FROM items WHERE account_id = ? ORDER BY created_at DESC", (acc_id,))
            items_raw = cur.fetchall()
            items = []
            for row in items_raw:
                d = dict(row)
                d['display_type'] = get_display_type(d)
                items.append(d)
            collections = [dict(row) for row in conn.execute('''
                SELECT c.*, COUNT(ci.item_id) AS item_count
                FROM collections c
                LEFT JOIN collection_items ci ON ci.collection_id = c.id
                WHERE c.account_id = ?
                GROUP BY c.id
                ORDER BY c.created_at DESC
            ''', (acc_id,)).fetchall()]
        return render_template('account.html', account_id=acc_id, items=items, collections=collections)
    else:
        items = []
        anon_uploads = session.get('anon_uploads', [])
        if anon_uploads:
            with get_db() as conn:
                placeholders = ','.join('?' for _ in anon_uploads)
                query = f"SELECT * FROM items WHERE id IN ({placeholders}) ORDER BY created_at DESC"
                cur = conn.execute(query, anon_uploads)
                for row in cur.fetchall():
                    d = dict(row)
                    d['display_type'] = get_display_type(d)
                    items.append(d)
        return render_template('account_login.html', items=items)

@app.route('/collections', methods=['POST'])
def create_collection():
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))

    name = request.form.get('name', '').strip()
    if not name or len(name) > 80:
        return 'error: collection name must be between 1 and 80 characters', 400

    password = request.form.get('password', '')
    password_hash = generate_password_hash(password) if password else None
    custom_id = request.form.get('custom_id')
    url_format = request.form.get('url_format')
    now = int(time.time())
    collection_id, err = get_or_generate_item_id(custom_id, url_format, now)
    if err:
        return err, 400
    selected_ids = list(dict.fromkeys(request.form.getlist('item_ids')))
    folder_path = sanitize_folder_path(request.form.get('folder_path', ''))

    with get_db() as conn:
        conn.execute(
            'INSERT INTO collections (id, account_id, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)',
            (collection_id, account_id, name, password_hash, now)
        )
        if selected_ids:
            placeholders = ','.join('?' for _ in selected_ids)
            owned = conn.execute(
                f'SELECT id, password_hash FROM items WHERE account_id = ? AND id IN ({placeholders})',
                (account_id, *selected_ids)
            ).fetchall()
            if any(row['password_hash'] for row in owned) and not password_hash:
                conn.rollback()
                return 'error: password-protected items require a password-protected collection', 400
            for row in owned:
                attach_item_to_collection(conn, collection_id, row['id'], account_id, folder_path)
        conn.commit()

    if password_hash:
        session[f'collection_auth_{collection_id}'] = True
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/init_batch', methods=['POST'])
def init_batch_collection():
    desktop_account = authenticate_desktop_client()
    if desktop_account:
        session['account_id'] = desktop_account
        if not desktop_upload_allowed(desktop_account):
            return 'error: desktop upload rate limit reached. try again later', 429
    else:
        req_data = request.get_json(silent=True) or request.form
        batch_id = secure_filename(req_data.get('batch_id', ''))
        batch = session.get('verified_upload_batch', {})
        batch_verified = (
            batch_id and batch.get('id') == batch_id
            and int(time.time()) - int(batch.get('verified_at', 0)) <= 3600
            and int(batch.get('uses_left', 0)) > 0
        )
        if batch_verified:
            batch['uses_left'] -= 1
            session['verified_upload_batch'] = batch
            session.modified = True
        elif not verify_turnstile():
            return 'error: verification failed. complete the verification and try again', 403
        elif batch_id:
            session['verified_upload_batch'] = {'id': batch_id, 'verified_at': int(time.time()), 'uses_left': 999}
            session.modified = True

    account_id = session.get('account_id')
    if not account_id:
        account_id = generate_account_id()
        with get_db() as conn:
            conn.execute("INSERT INTO accounts (account_id, created_at) VALUES (?, ?)", (account_id, int(time.time())))
            conn.commit()
        session['account_id'] = account_id
        session.modified = True

    req_data = request.get_json(silent=True) or request.form
    requested_name = str(req_data.get('name') or '').strip()
    name = requested_name or "new collection"
    if len(name) > 80:
        name = name[:80]

    password = str(req_data.get('password') or '')
    password_hash = generate_password_hash(password) if password else None
    custom_id = req_data.get('custom_id')
    url_format = req_data.get('url_format')
    now = int(time.time())

    collection_id, err = get_or_generate_item_id(custom_id, url_format, now)
    if err:
        return err, 400

    with get_db() as conn:
        conn.execute(
            'INSERT INTO collections (id, account_id, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)',
            (collection_id, account_id, name, password_hash, now)
        )
        conn.commit()

    if password_hash:
        session[f'collection_auth_{collection_id}'] = True

    return {
        'id': collection_id,
        'name': name,
        'url': url_for('collection_view', collection_id=collection_id),
        'download_zip_url': url_for('download_collection_zip', collection_id=collection_id),
    }, 200

@app.route('/collections/auto', methods=['POST'])
def create_automatic_collection():
    account_id = session.get('account_id')
    if not account_id:
        account_id = generate_account_id()
        with get_db() as conn:
            conn.execute("INSERT INTO accounts (account_id, created_at) VALUES (?, ?)", (account_id, int(time.time())))
            conn.commit()
        session['account_id'] = account_id
        session.modified = True

    payload = request.get_json(silent=True) or {}
    selected_ids = list(dict.fromkeys(payload.get('item_ids') or []))[:100]
    allow_single = bool(payload.get('allow_single'))
    min_required = 1 if allow_single else 2
    if len(selected_ids) < min_required or not all(isinstance(item_id, str) for item_id in selected_ids):
        return {'error': f'At least {min_required} valid upload{"s are" if min_required > 1 else " is"} required.'}, 400
    requested_name = str(payload.get('name') or '').strip()
    name = requested_name or (f'{len(selected_ids)} uploaded file' if len(selected_ids) == 1 else f'{len(selected_ids)} uploaded files')
    if len(name) > 80:
        name = name[:80]
    password = str(payload.get('password') or '')
    password_hash = generate_password_hash(password) if password else None
    custom_id = payload.get('custom_id')
    url_format = payload.get('url_format')
    now = int(time.time())
    collection_id, err = get_or_generate_item_id(custom_id, url_format, now)
    if err:
        return {'error': err}, 400

    anon_uploads = set(session.get('anon_uploads', []))
    with get_db() as conn:
        claim_ids = [iid for iid in selected_ids if iid in anon_uploads]
        if claim_ids:
            claim_placeholders = ','.join('?' for _ in claim_ids)
            conn.execute(
                f'UPDATE items SET account_id = ? WHERE account_id IS NULL AND id IN ({claim_placeholders})',
                (account_id, *claim_ids)
            )
            conn.commit()

        placeholders = ','.join('?' for _ in selected_ids)
        owned = conn.execute(
            f'SELECT id, password_hash FROM items WHERE account_id = ? AND id IN ({placeholders})',
            (account_id, *selected_ids)
        ).fetchall()
        if len(owned) != len(selected_ids):
            return {'error': 'One or more uploads could not be added.'}, 400
        if any(row['password_hash'] for row in owned) and not password_hash:
            return {'error': 'A collection password is required for protected uploads.'}, 400
        conn.execute(
            'INSERT INTO collections (id, account_id, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)',
            (collection_id, account_id, name, password_hash, now)
        )
        folders = payload.get('folders') or {}
        for row in owned:
            attach_item_to_collection(conn, collection_id, row['id'], account_id, folders.get(row['id'], ''))
        conn.commit()
    if password_hash:
        session[f'collection_auth_{collection_id}'] = True
    return {
        'id': collection_id,
        'name': name,
        'url': url_for('collection_view', collection_id=collection_id),
        'raw_url': url_for('collection_view', collection_id=collection_id, raw='1'),
    }

@app.route('/collections/<collection_id>/name', methods=['POST'])
def rename_collection_json(collection_id):
    account_id = session.get('account_id')
    if not account_id:
        abort(401)
    payload = request.get_json(silent=True) or {}
    name = str(payload.get('name') or '').strip()
    if not name or len(name) > 80:
        return {'error': 'Collection name must be between 1 and 80 characters.'}, 400
    with get_db() as conn:
        result = conn.execute(
            'UPDATE collections SET name = ? WHERE id = ? AND account_id = ?',
            (name, collection_id, account_id)
        )
        conn.commit()
    if not result.rowcount:
        abort(404)
    return {'ok': True, 'name': name}

@app.route('/collections/<collection_id>/items', methods=['POST'])
def add_collection_items(collection_id):
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    selected_ids = list(dict.fromkeys(request.form.getlist('item_ids')))
    if not selected_ids:
        return 'error: select at least one item', 400
    folder_path = sanitize_folder_path(request.form.get('folder_path', ''))

    with get_db() as conn:
        collection = conn.execute(
            'SELECT * FROM collections WHERE id = ? AND account_id = ?',
            (collection_id, account_id)
        ).fetchone()
        if not collection:
            abort(404)
        placeholders = ','.join('?' for _ in selected_ids)
        owned = conn.execute(
            f'SELECT id, password_hash FROM items WHERE account_id = ? AND id IN ({placeholders})',
            (account_id, *selected_ids)
        ).fetchall()
        if any(row['password_hash'] for row in owned) and not collection['password_hash']:
            return 'error: password-protected items require a password-protected collection', 400
        for row in owned:
            attach_item_to_collection(conn, collection_id, row['id'], account_id, folder_path)
        conn.commit()
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/add-items', methods=['POST'])
def add_items_from_account():
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    collection_id = request.form.get('collection_id', '')
    selected_ids = list(dict.fromkeys(request.form.getlist('item_ids')))
    if not collection_id or not selected_ids:
        return 'error: choose a collection and at least one item', 400
    folder_path = sanitize_folder_path(request.form.get('folder_path', ''))
    with get_db() as conn:
        collection = conn.execute(
            'SELECT * FROM collections WHERE id = ? AND account_id = ?',
            (collection_id, account_id)
        ).fetchone()
        if not collection:
            abort(404)
        placeholders = ','.join('?' for _ in selected_ids)
        owned = conn.execute(
            f'SELECT id, password_hash FROM items WHERE account_id = ? AND id IN ({placeholders})',
            (account_id, *selected_ids)
        ).fetchall()
        if any(row['password_hash'] for row in owned) and not collection['password_hash']:
            return 'error: password-protected items require a password-protected collection', 400
        for row in owned:
            attach_item_to_collection(conn, collection_id, row['id'], account_id, folder_path)
        conn.commit()
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/<collection_id>/items/move', methods=['POST'])
def move_collection_items(collection_id):
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    selected_ids = list(dict.fromkeys(request.form.getlist('item_ids')))
    if not selected_ids:
        return 'error: select at least one item', 400
    folder_path = sanitize_folder_path(request.form.get('folder_path', ''))
    with get_db() as conn:
        if not conn.execute(
            'SELECT 1 FROM collections WHERE id = ? AND account_id = ?',
            (collection_id, account_id)
        ).fetchone():
            abort(404)
        placeholders = ','.join('?' for _ in selected_ids)
        conn.execute(
            f'UPDATE collection_items SET folder_path = ? WHERE collection_id = ? AND item_id IN ({placeholders})',
            (folder_path, collection_id, *selected_ids)
        )
        conn.commit()
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/<collection_id>/items/remove-selected', methods=['POST'])
def remove_selected_collection_items(collection_id):
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    selected_ids = list(dict.fromkeys(request.form.getlist('item_ids')))
    if not selected_ids:
        return 'error: select at least one item', 400
    with get_db() as conn:
        if not conn.execute(
            'SELECT 1 FROM collections WHERE id = ? AND account_id = ?',
            (collection_id, account_id)
        ).fetchone():
            abort(404)
        placeholders = ','.join('?' for _ in selected_ids)
        conn.execute(
            f'DELETE FROM collection_items WHERE collection_id = ? AND item_id IN ({placeholders})',
            (collection_id, *selected_ids)
        )
        conn.commit()
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/<collection_id>/items/<item_id>/rename', methods=['POST'])
def rename_collection_item(collection_id, item_id):
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    display_name = request.form.get('display_name', '').strip()
    if len(display_name) > 180 or any(ord(char) < 32 for char in display_name):
        return 'error: name must be 180 characters or fewer and contain no control characters', 400
    with get_db() as conn:
        owned = conn.execute('''
            SELECT 1 FROM collection_items ci
            JOIN collections c ON c.id = ci.collection_id
            WHERE ci.collection_id = ? AND ci.item_id = ? AND c.account_id = ?
        ''', (collection_id, item_id, account_id)).fetchone()
        if not owned:
            abort(404)
        conn.execute(
            'UPDATE collection_items SET display_name = ? WHERE collection_id = ? AND item_id = ?',
            (display_name or None, collection_id, item_id)
        )
        conn.commit()
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/<collection_id>/settings', methods=['POST'])
def update_collection_settings(collection_id):
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    name = request.form.get('name', '').strip()
    if not name or len(name) > 80:
        return 'error: collection name must be between 1 and 80 characters', 400
    new_password = request.form.get('password', '')
    remove_password = request.form.get('remove_password') == '1'
    with get_db() as conn:
        collection = conn.execute(
            'SELECT * FROM collections WHERE id = ? AND account_id = ?',
            (collection_id, account_id)
        ).fetchone()
        if not collection:
            abort(404)
        password_hash = collection['password_hash']
        if remove_password:
            protected_item = conn.execute('''
                SELECT 1 FROM collection_items ci
                JOIN items i ON i.id = ci.item_id
                WHERE ci.collection_id = ? AND i.password_hash IS NOT NULL
                LIMIT 1
            ''', (collection_id,)).fetchone()
            if protected_item:
                return 'error: remove password-protected items before removing the collection password', 400
            password_hash = None
        elif new_password:
            password_hash = generate_password_hash(new_password)
            session[f'collection_auth_{collection_id}'] = True
        conn.execute(
            'UPDATE collections SET name = ?, password_hash = ? WHERE id = ?',
            (name, password_hash, collection_id)
        )
        conn.commit()
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/<collection_id>/items/<item_id>/remove', methods=['POST'])
def remove_collection_item(collection_id, item_id):
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    with get_db() as conn:
        owned = conn.execute(
            'SELECT 1 FROM collections WHERE id = ? AND account_id = ?',
            (collection_id, account_id)
        ).fetchone()
        if not owned:
            abort(404)
        conn.execute(
            'DELETE FROM collection_items WHERE collection_id = ? AND item_id = ?',
            (collection_id, item_id)
        )
        conn.commit()
    return redirect(url_for('collection_view', collection_id=collection_id))

@app.route('/collections/<collection_id>/delete', methods=['POST'])
def delete_collection(collection_id):
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    delete_contents = request.form.get('delete_contents') == '1'
    if delete_contents and request.form.get('contents_confirmation') not in ('DELETE', f'DELETE {collection_id}'):
        abort(400, description='Collection contents deletion was not confirmed.')

    with get_db() as conn:
        collection = conn.execute(
            'SELECT id FROM collections WHERE id = ? AND account_id = ?',
            (collection_id, account_id)
        ).fetchone()
        if not collection:
            abort(404)

        if delete_contents:
            items = conn.execute('''
                SELECT DISTINCT i.*
                FROM items i
                JOIN collection_items ci ON ci.item_id = i.id
                WHERE ci.collection_id = ? AND i.account_id = ?
            ''', (collection_id, account_id)).fetchall()
            for item in items:
                remove_physical_item_file(item)
                conn.execute('DELETE FROM tombstones WHERE id = ?', (item['id'],))
                conn.execute(
                    'DELETE FROM items WHERE id = ? AND account_id = ?',
                    (item['id'], account_id)
                )

        conn.execute('DELETE FROM collections WHERE id = ?', (collection_id,))
        conn.commit()
    session.pop(f'collection_auth_{collection_id}', None)
    return redirect(url_for('account'))

def get_collection_payload(collection_id):
    now = int(time.time())
    with get_db() as conn:
        collection = conn.execute('SELECT * FROM collections WHERE id = ?', (collection_id,)).fetchone()
        if not collection:
            return None, []
        rows = conn.execute('''
            SELECT i.*, ci.folder_path, ci.display_name, ci.added_at
            FROM collection_items ci
            JOIN items i ON i.id = ci.item_id
            WHERE ci.collection_id = ? AND i.expires_at > ?
            ORDER BY ci.folder_path, i.type, COALESCE(i.filename, i.id)
        ''', (collection_id, now)).fetchall()
    items = []
    for row in rows:
        item = dict(row)
        item['display_type'] = get_display_type(item)
        default_label = item['filename'] if item['type'] == 'file' else f"paste-{item['id']}.txt"
        item['label'] = item.get('display_name') or default_label
        items.append(item)
    return dict(collection), items

@app.route('/c/<collection_id>', methods=['GET', 'POST'])
def collection_view(collection_id):
    collection, items = get_collection_payload(collection_id)
    if not collection:
        abort(404)

    if collection['password_hash']:
        if request.method == 'POST':
            if not verify_turnstile():
                return render_template('collection_unlock.html', collection=collection, error='verification failed. try again.'), 403
            if not check_password_hash(collection['password_hash'], request.form.get('password', '')):
                return render_template('collection_unlock.html', collection=collection, error='incorrect password'), 401
            session[f'collection_auth_{collection_id}'] = True
        elif not session.get(f'collection_auth_{collection_id}'):
            return render_template('collection_unlock.html', collection=collection)

    account_id = session.get('account_id')
    is_owner = account_id == collection['account_id']
    available_items = []
    if is_owner:
        with get_db() as conn:
            available_items = [dict(row) for row in conn.execute('''
                SELECT * FROM items
                WHERE account_id = ? AND expires_at > ?
                  AND id NOT IN (SELECT item_id FROM collection_items WHERE collection_id = ?)
                ORDER BY created_at DESC
            ''', (account_id, int(time.time()), collection_id)).fetchall()]
    return render_template(
        'collection_legacy.html' if is_legacy_browser() else 'collection.html',
        collection=collection,
        items=items,
        tree=build_collection_tree(items),
        is_owner=is_owner,
        available_items=available_items,
        raw_download_mode=request.args.get('raw') == '1',
    )

@app.route('/c/<collection_id>/download.zip', methods=['GET', 'POST'])
def download_collection_zip(collection_id):
    collection, items = get_collection_payload(collection_id)
    if not collection:
        abort(404)
    if collection['password_hash'] and not session.get(f'collection_auth_{collection_id}'):
        return redirect(url_for('collection_view', collection_id=collection_id))

    selected = set(request.form.getlist('item_ids'))
    chosen = [item for item in items if not selected or item['id'] in selected]
    if not chosen:
        return 'error: select at least one item', 400

    temp = tempfile.NamedTemporaryFile(prefix='nullhost_collection_', suffix='.zip', delete=False)
    temp_path = temp.name
    temp.close()
    used_paths = set()
    with zipfile.ZipFile(temp_path, 'w', compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
        for item in chosen:
            folder = sanitize_folder_path(item.get('folder_path', ''))
            filename = secure_filename(item['label']) or item['id']
            archive_name = '/'.join(filter(None, (folder, filename)))
            if archive_name in used_paths:
                stem, extension = os.path.splitext(filename)
                archive_name = '/'.join(filter(None, (folder, f'{stem}-{item["id"]}{extension}')))
            used_paths.add(archive_name)
            if item['type'] == 'file':
                source = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
                if os.path.exists(source):
                    archive.write(source, archive_name)
            else:
                archive.writestr(archive_name, item['content'] or '')

    @after_this_request
    def remove_archive(response):
        try:
            os.remove(temp_path)
        except OSError:
            pass
        return response

    download_name = secure_filename(collection['name']) or collection['id']
    return send_file(temp_path, as_attachment=True, download_name=f'{download_name}.zip')

def qr_response(target_url):
    box_size = None
    size_param = request.args.get('size')
    if size_param:
        try:
            box_size = max(1, min(20, int(size_param)))
        except (ValueError, TypeError):
            box_size = None
    if box_size is None and (is_legacy_browser() or 'nullhost-j2me' in request.headers.get('User-Agent', '').lower() or 'nullhost-symbian' in request.headers.get('User-Agent', '').lower()):
        box_size = 5

    if box_size:
        qr = qrcode.QRCode(box_size=box_size, border=2)
        qr.add_data(target_url)
        qr.make(fit=True)
        image = qr.make_image(fill_color="black", back_color="white")
    else:
        image = qrcode.make(target_url)
    buffer = io.BytesIO()
    image.save(buffer, format='PNG')
    buffer.seek(0)
    return send_file(buffer, mimetype='image/png', max_age=3600)

@app.route('/c/<collection_id>/qr.png')
def collection_qr(collection_id):
    with get_db() as conn:
        if not conn.execute('SELECT 1 FROM collections WHERE id = ?', (collection_id,)).fetchone():
            abort(404)
    return qr_response(url_for('collection_view', collection_id=collection_id, _external=True))

@app.route('/v/<item_id>/qr.png')
def item_qr(item_id):
    with get_db() as conn:
        if not conn.execute('SELECT 1 FROM items WHERE id = ?', (item_id,)).fetchone():
            if conn.execute('SELECT 1 FROM collections WHERE id = ?', (item_id,)).fetchone():
                return redirect(url_for('collection_qr', collection_id=item_id))
            abort(404)
    destination = request.args.get('target', 'info')
    if destination == 'raw':
        target_url = url_for('raw', item_id=item_id, _external=True)
    else:
        target_url = url_for('overview', item_id=item_id, _external=True)
    return qr_response(target_url)

@app.route('/rename/<item_id>', methods=['POST'])
def rename_item(item_id):
    
    new_name = request.form.get('new_name')
    if not new_name:
        return "error: new name required", 400
        
    new_name = secure_filename(new_name)
    if not new_name:
        return "error: invalid filename", 400
        
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,))
        item = cur.fetchone()
        
        anon_uploads = session.get('anon_uploads', [])
        is_authorized = False
        
        if item:
            if item['account_id']:
                if 'account_id' in session and item['account_id'] == session['account_id']:
                    is_authorized = True
            else:
                if item_id in anon_uploads:
                    is_authorized = True
                    
        if not is_authorized or item['type'] != 'file':
            return "error: unauthorized or not found", 401
            
        old_filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
        new_filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + new_name)
        
        if os.path.exists(old_filepath):
            os.rename(old_filepath, new_filepath)
            
        conn.execute("UPDATE items SET filename = ? WHERE id = ?", (new_name, item_id))
        conn.commit()
        
    return redirect(url_for('account'))

@app.route('/delete/<item_id>', methods=['POST'])
def delete_item(item_id):
    
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,))
        item = cur.fetchone()
        
        anon_uploads = session.get('anon_uploads', [])
        is_authorized = False
        
        if item:
            if item['account_id']:
                if 'account_id' in session and item['account_id'] == session['account_id']:
                    is_authorized = True
            else:
                if item_id in anon_uploads:
                    is_authorized = True
                    
        if not is_authorized:
            return "error: unauthorized or not found", 401
        
        remove_physical_item_file(item)
                
        conn.execute("DELETE FROM items WHERE id = ?", (item_id,))
        conn.commit()
        
    return redirect(url_for('account'))

@app.route('/items/delete-selected', methods=['POST'])
def delete_selected_items():
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    selected_ids = list(dict.fromkeys(request.form.getlist('item_ids')))
    if request.form.get('confirm_delete') != '1':
        return 'error: deletion was not confirmed', 400
    if not selected_ids:
        return 'error: select at least one upload', 400
    with get_db() as conn:
        placeholders = ','.join('?' for _ in selected_ids)
        items = conn.execute(
            f'SELECT * FROM items WHERE account_id = ? AND id IN ({placeholders})',
            (account_id, *selected_ids)
        ).fetchall()
        if len(items) != len(selected_ids):
            return 'error: one or more uploads were not found', 404
        for item in items:
            remove_physical_item_file(item)
        conn.execute(
            f'DELETE FROM tombstones WHERE id IN ({placeholders})',
            selected_ids
        )
        conn.execute(
            f'DELETE FROM items WHERE account_id = ? AND id IN ({placeholders})',
            (account_id, *selected_ids)
        )
        conn.commit()
    return redirect(url_for('account'))

@app.route('/api/items/delete-uploaded', methods=['POST'])
def delete_recent_desktop_uploads():
    desktop_account = authenticate_desktop_client()
    if desktop_account:
        session['account_id'] = desktop_account
    account_id = session.get('account_id')
    if not account_id:
        return {'error': 'Authentication required.'}, 401

    payload = request.get_json(silent=True) or {}
    if payload.get('confirm_delete') is not True:
        return {'error': 'Deletion was not confirmed.'}, 400
    selected_ids = list(dict.fromkeys(payload.get('item_ids') or []))[:100]
    if not selected_ids or not all(isinstance(item_id, str) for item_id in selected_ids):
        return {'error': 'At least one valid upload is required.'}, 400
    collection_id = str(payload.get('collection_id') or '').strip()

    with get_db() as conn:
        placeholders = ','.join('?' for _ in selected_ids)
        items = conn.execute(
            f'SELECT * FROM items WHERE account_id = ? AND id IN ({placeholders})',
            (account_id, *selected_ids)
        ).fetchall()
        if len(items) != len(selected_ids):
            return {'error': 'One or more uploads were not found or are not yours.'}, 404
        if collection_id:
            owned_collection = conn.execute(
                'SELECT 1 FROM collections WHERE id = ? AND account_id = ?',
                (collection_id, account_id)
            ).fetchone()
            if not owned_collection:
                return {'error': 'Collection was not found or is not yours.'}, 404

        for item in items:
            remove_physical_item_file(item)
        conn.execute(f'DELETE FROM tombstones WHERE id IN ({placeholders})', selected_ids)
        conn.execute(
            f'DELETE FROM items WHERE account_id = ? AND id IN ({placeholders})',
            (account_id, *selected_ids)
        )
        if collection_id:
            conn.execute(
                'DELETE FROM collections WHERE id = ? AND account_id = ?',
                (collection_id, account_id)
            )
        conn.commit()

    return {'success': True, 'deleted': len(selected_ids)}

@app.route('/account/nuke', methods=['POST'])
def nuke_account():
    account_id = session.get('account_id')
    if not account_id:
        return redirect(url_for('account'))
    if request.form.get('confirmation', '') != 'NUKE':
        return 'error: type NUKE exactly to delete the account', 400
    with get_db() as conn:
        items = conn.execute('SELECT * FROM items WHERE account_id = ?', (account_id,)).fetchall()
        item_ids = [item['id'] for item in items]
        for item in items:
            remove_physical_item_file(item)
        if item_ids:
            placeholders = ','.join('?' for _ in item_ids)
            conn.execute(f'DELETE FROM tombstones WHERE id IN ({placeholders})', item_ids)
        conn.execute('DELETE FROM items WHERE account_id = ?', (account_id,))
        conn.execute('DELETE FROM collections WHERE account_id = ?', (account_id,))
        conn.execute('DELETE FROM accounts WHERE account_id = ?', (account_id,))
        conn.commit()
    session.clear()
    return redirect(url_for('account'))

@app.route('/v/<item_id>', methods=['GET', 'POST'])
def overview(item_id):
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM tombstones WHERE id = ?", (item_id,))
        tombstone = cur.fetchone()
        if tombstone and tombstone['expires_at'] > int(time.time()):
            return render_template('tombstone.html', item_id=item_id, reason=tombstone['reason'], expires_at=tombstone['expires_at']), 451
            
        cur = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,))
        row = cur.fetchone()
        
    if not row:
        with get_db() as conn:
            if conn.execute("SELECT 1 FROM collections WHERE id = ?", (item_id,)).fetchone():
                return redirect(url_for('collection_view', collection_id=item_id))
        return "error: not found or expired", 404

    item = dict(row)
    item['display_type'] = get_display_type(item)

    now = int(time.time())
    if item['expires_at'] <= now:
        return "error: not found or expired", 404

    # Password check
    if item['password_hash']:
        if request.method == 'POST':
            if not verify_turnstile():
                return render_template('password.html', item_id=item_id, target='overview', error='verification failed. try again.'), 403
            pwd = request.form.get('password')
            if not check_password_hash(item['password_hash'], pwd):
                return "error: incorrect password", 401
            # Set a temporary session flag to allow viewing/downloading
            session[f'auth_{item_id}'] = True
        elif not session.get(f'auth_{item_id}'):
            return render_template('password.html', item_id=item_id, target='overview')

    # We do not trigger burn_after_read on the overview page, only on the raw download
    template = 'overview_legacy.html' if is_legacy_browser() else 'overview.html'
    return render_template(template, item=item)

@app.route('/v/<item_id>/<filename>', methods=['GET', 'POST'])
@app.route('/raw/<item_id>', methods=['GET', 'POST'])
@app.route('/raw/<item_id>/<filename>', methods=['GET', 'POST'])
def raw(item_id, filename=None, force_download=False):
    with get_db() as conn:
        cur = conn.execute("SELECT * FROM tombstones WHERE id = ?", (item_id,))
        tombstone = cur.fetchone()
        if tombstone and tombstone['expires_at'] > int(time.time()):
            return render_template('tombstone.html', item_id=item_id, reason=tombstone['reason'], expires_at=tombstone['expires_at']), 451
            
        cur = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,))
        item = cur.fetchone()
        
    if not item:
        with get_db() as conn:
            if conn.execute("SELECT 1 FROM collections WHERE id = ?", (item_id,)).fetchone():
                return redirect(url_for('download_collection_zip', collection_id=item_id))
        return "error: not found or expired", 404

    now = int(time.time())
    if item['expires_at'] <= now:
        return "error: not found or expired", 404

    if item['password_hash']:
        param_pwd = (request.args.get('password') or request.headers.get('X-Nullhost-Password') or '').strip()
        if param_pwd and check_password_hash(item['password_hash'], param_pwd):
            session[f'auth_{item_id}'] = True
        elif request.method == 'POST':
            if not verify_turnstile():
                return render_template('password.html', item_id=item_id, target='raw', action_url=request.path, error='verification failed. try again.'), 403
            pwd = request.form.get('password')
            if not check_password_hash(item['password_hash'], pwd):
                return "error: incorrect password", 401
            session[f'auth_{item_id}'] = True
        elif not session.get(f'auth_{item_id}'):
            return render_template('password.html', item_id=item_id, target='raw', action_url=request.path)

    new_expires = item['expires_at']
    if item['dynamic_expiry'] and item['expires_at'] <= 2000000000:
        extension_cap = item['created_at'] + (180 * 86400)
        if item['expires_at'] < extension_cap:
            new_expires = min(item['expires_at'] + (3 * 86400), extension_cap)

    if item['type'] == 'file':
        as_attachment = force_download or request.args.get('dl') == '1'
        resp = make_response(send_from_directory(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'], as_attachment=as_attachment, download_name=item['filename']))
        active_types = ('text/html', 'application/xhtml+xml', 'image/svg+xml', 'text/xml', 'application/xml')
        if str(item['mimetype'] or '').split(';', 1)[0].lower() in active_types:
            resp.headers['Content-Disposition'] = f'attachment; filename="{secure_filename(item["filename"]) or "download"}"'
        resp.headers['Content-Security-Policy'] = "sandbox; default-src 'none'; frame-ancestors 'none'"
    else:
        resp = make_response(item['content'])
        resp.headers['Content-Type'] = 'text/plain; charset=utf-8'
        if force_download or request.args.get('dl') == '1':
            resp.headers['Content-Disposition'] = 'attachment; filename="paste.txt"'

    if item['is_partial']:
        resp.headers['X-Nullhost-Partial'] = '1'

    burn_triggered = False
    if item['burn_after_read'] > 0 and (item['views'] + 1) >= item['burn_after_read']:
        burn_triggered = True

    with get_db() as conn:
        conn.execute("UPDATE items SET views = views + 1, expires_at = ? WHERE id = ?", (new_expires, item_id))
        if burn_triggered:
            conn.execute("DELETE FROM items WHERE id = ?", (item_id,))
        conn.commit()

    if burn_triggered and item['type'] == 'file':
        filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
        if os.path.exists(filepath):
            try:
                os.remove(filepath)
            except:
                pass

    return resp

@app.route('/v/<item_id>/<filename>/download', methods=['GET', 'POST'])
def download(item_id, filename):
    return raw(item_id, filename, force_download=True)

@app.route('/admin', methods=['GET', 'POST'])
def admin_gate():
    ip = get_client_ip() or 'unknown'
    now = time.time()
    last_failed = admin_login_attempts.get(ip, 0)
    cooldown_remaining = max(0, int(10 - (now - last_failed)))
    
    if request.method == 'POST':
        action = request.form.get('action')
        if action == 'logout':
            session.pop('is_admin', None)
            return redirect(url_for('index'))
            
        if cooldown_remaining > 0:
            return render_template('admin_login.html', error=f"cooldown active. please wait {cooldown_remaining} seconds.", cooldown=cooldown_remaining), 429
            
        totp_code = request.form.get('totp_code', '')
        if verify_admin_totp(totp_code):
            session['is_admin'] = True
            if ip in admin_login_attempts:
                del admin_login_attempts[ip]
            return redirect(url_for('admin_dashboard'))
        else:
            admin_login_attempts[ip] = time.time()
            return render_template('admin_login.html', error="incorrect 6-digit authenticator code.", cooldown=10), 429
            
    if session.get('is_admin'):
        return redirect(url_for('admin_dashboard'))
    return render_template('admin_login.html', cooldown=cooldown_remaining)

@app.route('/gelaxiz_admin_gate', methods=['GET', 'POST'])
def gelaxiz_admin_gate_redirect():
    return redirect(url_for('admin_gate'))

@app.route('/admin_dashboard')
def admin_dashboard():
    if not session.get('is_admin'):
        return redirect(url_for('admin_gate'))
    q = request.args.get('q', '').strip()
    with get_db() as conn:
        items_count = conn.execute("SELECT COUNT(*) as c FROM items").fetchone()['c']
        files_count = conn.execute("SELECT COUNT(*) as c FROM items WHERE type='file'").fetchone()['c']
        pastes_count = conn.execute("SELECT COUNT(*) as c FROM items WHERE type='paste'").fetchone()['c']
        accs_count = conn.execute("SELECT COUNT(*) as c FROM accounts").fetchone()['c']
        tomb_count = conn.execute("SELECT COUNT(*) as c FROM tombstones WHERE expires_at > ?", (int(time.time()),)).fetchone()['c']
        total_size = conn.execute("SELECT SUM(size) as s FROM items").fetchone()['s'] or 0
        
        if q:
            items = conn.execute("SELECT * FROM items WHERE id LIKE ? OR filename LIKE ? OR account_id LIKE ? ORDER BY created_at DESC LIMIT 100", (f'%{q}%', f'%{q}%', f'%{q}%')).fetchall()
        else:
            items = conn.execute("SELECT * FROM items ORDER BY created_at DESC LIMIT 100").fetchall()
        accounts = conn.execute("SELECT a.account_id, a.created_at, COUNT(i.id) as item_count, SUM(i.size) as total_size FROM accounts a LEFT JOIN items i ON a.account_id = i.account_id GROUP BY a.account_id ORDER BY a.created_at DESC LIMIT 50").fetchall()
        tombstones = conn.execute("SELECT * FROM tombstones ORDER BY deleted_at DESC LIMIT 50").fetchall()
        
    stats = {
        'items_count': items_count,
        'files_count': files_count,
        'pastes_count': pastes_count,
        'accs_count': accs_count,
        'tomb_count': tomb_count,
        'total_size_mb': round(total_size / 1048576, 2) if total_size else 0
    }
    return render_template('admin_dashboard.html', stats=stats, items=items, accounts=accounts, tombstones=tombstones, now=int(time.time()), q=q)

@app.route('/admin/update_tombstone_reason/<tomb_id>', methods=['POST'])
def admin_update_tombstone_reason(tomb_id):
    if not session.get('is_admin'): return abort(403)
    new_reason = request.form.get('reason', '').strip()
    if new_reason:
        with get_db() as conn:
            conn.execute("UPDATE tombstones SET reason = ? WHERE id = ?", (new_reason, tomb_id))
            conn.commit()
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/delete_item/<item_id>', methods=['POST'])
def admin_delete_item(item_id):
    if not session.get('is_admin'): return abort(403)
    with get_db() as conn:
        item = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()
        if item and item['type'] == 'file':
            filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
            if os.path.exists(filepath):
                try: os.remove(filepath)
                except: pass
        conn.execute("DELETE FROM items WHERE id = ?", (item_id,))
        conn.commit()
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/takedown_item/<item_id>', methods=['POST'])
def admin_takedown_item(item_id):
    if not session.get('is_admin'): return abort(403)
    reason = request.form.get('reason', 'Third-party takedown request / Legal Notice')
    now = int(time.time())
    expires_at = now + 7 * 86400
    with get_db() as conn:
        item = conn.execute("SELECT * FROM items WHERE id = ?", (item_id,)).fetchone()
        if item and item['type'] == 'file':
            filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
            if os.path.exists(filepath):
                try: os.remove(filepath)
                except: pass
        conn.execute("DELETE FROM items WHERE id = ?", (item_id,))
        conn.execute("INSERT OR REPLACE INTO tombstones (id, reason, deleted_at, expires_at) VALUES (?, ?, ?, ?)", (item_id, reason, now, expires_at))
        conn.commit()
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/delete_account/<account_id>', methods=['POST'])
def admin_delete_account(account_id):
    if not session.get('is_admin'): return abort(403)
    with get_db() as conn:
        items = conn.execute("SELECT * FROM items WHERE account_id = ?", (account_id,)).fetchall()
        for item in items:
            if item['type'] == 'file':
                filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
                if os.path.exists(filepath):
                    try: os.remove(filepath)
                    except: pass
        conn.execute("DELETE FROM items WHERE account_id = ?", (account_id,))
        conn.execute("DELETE FROM accounts WHERE account_id = ?", (account_id,))
        conn.commit()
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/revoke_tombstone/<tomb_id>', methods=['POST'])
def admin_revoke_tombstone(tomb_id):
    if not session.get('is_admin'): return abort(403)
    with get_db() as conn:
        conn.execute("DELETE FROM tombstones WHERE id = ?", (tomb_id,))
        conn.commit()
    return redirect(url_for('admin_dashboard'))

@app.route('/admin/run_cleanup', methods=['POST'])
def admin_run_cleanup():
    if not session.get('is_admin'): return abort(403)
    with get_db() as conn:
        now = int(time.time())
        expired_items = conn.execute("SELECT id, type, filename FROM items WHERE expires_at <= ?", (now,)).fetchall()
        for item in expired_items:
            if item['type'] == 'file' and item['filename']:
                filepath = os.path.join(app.config['UPLOAD_FOLDER'], item['id'] + '_' + item['filename'])
                if os.path.exists(filepath):
                    try: os.remove(filepath)
                    except: pass
            conn.execute("DELETE FROM items WHERE id = ?", (item['id'],))
        conn.execute("DELETE FROM tombstones WHERE expires_at <= ?", (now,))
        conn.commit()
        
    # Clean up orphan temporary chunk files older than 24 hours
    try:
        upload_dir = app.config['UPLOAD_FOLDER']
        for fname in os.listdir(upload_dir):
            if fname.startswith('.tmp_'):
                fpath = os.path.join(upload_dir, fname)
                if os.path.exists(fpath) and (time.time() - os.path.getmtime(fpath)) > 86400:
                    try: os.remove(fpath)
                    except: pass
    except Exception:
        pass
        
    return redirect(url_for('admin_dashboard'))

@app.route('/dmca')
def dmca():
    return render_template('dmca.html')

@app.route('/about')
def about():
    return render_template('about.html')

if __name__ == '__main__':
    app.run(host='127.0.0.1', port=4443)
