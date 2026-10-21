# Nullhost

Fast, anonymous, ephemeral and permanent file & text sharing server with standalone native client applications for modern desktop and vintage mobile platforms.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Python 3.10+](https://img.shields.io/badge/Python-3.10%2B-brightgreen.svg)](https://www.python.org/)
[![Symbian Native](https://img.shields.io/badge/Symbian-Native%20(No%20Qt)-teal.svg)](#symbian--3-belle-fp2)
[![Java ME](https://img.shields.io/badge/Java%20ME-MIDP%202.0%20%2F%20JSR--75-orange.svg)](#universal-java-me-midlet)

---

## Features

- **Files & Pastes**: Share large files (up to 10 GB) or quick raw text snippets from the same lightweight web interface or native apps.
- **Ephemeral Expiration**: Choose retention periods (1 Hour, 24 Hours, 7 Days, 30 Days, or Never Expire).
- **Burn After Reading**: Files can self-destruct automatically after 1, 5, or 10 views.
- **Privacy & Encryption**: Password-protect uploads and collections. No tracking, no invasive cookies, no analytics.
- **Collections & Folders**: Organize multiple files into shareable collections or download them as a ZIP archive.
- **Optional Cloudflare Turnstile**: Operates 100% self-contained without third-party services. Turnstile CAPTCHA can be toggled on/off in `.env`.
- **Authentic Mobile API**: Cryptographic HMAC handshake allows verified native mobile clients to upload seamlessly.

---

## Self-Hosting Guide

### 1. Requirements
- Linux / macOS / Windows server
- Python 3.10+
- Reverse proxy (Caddy or Nginx)

### 2. Quick Setup

```bash
# Clone repository
git clone https://github.com/Gelaxiz/nullhost.git
cd nullhost

# Create and activate Python virtual environment
python3 -m venv venv
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Configure environment
cp .env.example .env
```

### 3. Configuration (`.env`)

Nullhost works out-of-the-box without editing `.env`:

```ini
# Set to 1 to enable Cloudflare Turnstile, or 0 to run completely standalone
ENABLE_TURNSTILE=0
TURNSTILE_SITE_KEY=
TURNSTILE_SECRET=

# Secret key for authenticating genuine mobile applications
NULLHOST_MOBILE_APP_KEY=nullhost_secret_mobile_app_key_2026
```

### 4. Running the Server

#### Development
```bash
python3 app.py
```

#### Production with Gunicorn
```bash
gunicorn -w 4 -b 127.0.0.1:5000 --timeout 120 app:app
```

---

## Reverse Proxy Setup

### Option A: Caddy (Recommended)

Caddy handles automatic HTTPS and large file transfers with a minimal configuration:

```caddy
files.example.com {
    reverse_proxy 127.0.0.1:5000 {
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto {scheme}
    }
    request_body {
        max_size 10GB
    }
}
```

### Option B: Nginx

```nginx
server {
    server_name files.example.com;
    client_max_body_size 10G;

    location / {
        proxy_pass http://127.0.0.1:5000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

---

## Systemd Service

Create `/etc/systemd/system/nullhost.service`:

```ini
[Unit]
Description=Nullhost File Sharing Service
After=network.target

[Service]
Type=simple
User=www-data
Group=www-data
WorkingDirectory=/var/www/nullhost
ExecStart=/var/www/nullhost/venv/bin/gunicorn -w 4 -b 127.0.0.1:5000 --timeout 120 app:app
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now nullhost
```

---

## Standalone Client Applications

The repository includes dedicated standalone client applications built specifically for their target operating systems.

| Client | Platform | Binary | Source |
| :--- | :--- | :--- | :--- |
| **Symbian ^3 (Belle FP2)** | Symbian ^3, Anna, Belle FP2 (Nokia 808, 701, N8, etc.) | `client/Nullhost_Belle.sisx` | `client/symbian_belle/` |
| **Series 60 Keypad** | S60 3rd / 5th Edition Keypad (Nokia N95, E71, E72, etc.) | `client/Nullhost_S60.sis` | `client/s60_keypad/` |
| **Universal Java ME** | Any phone supporting MIDP 2.0 / CLDC 1.1 + JSR-75 | `client/Nullhost.jar` | `client/j2me/src/` |
| **Windows Desktop** | Windows 7 / 8 / 10 / 11 (.NET 4.5+) | `client/Nullhost.exe` | `client/windows/` |

### Symbian ^3 (Belle FP2) — Native C++
- **100% Native Symbian C++**: Written entirely in Symbian C++ (`inc/`, `src/`, `group/`, `data/`) using standard Symbian OS ROM libraries. **Zero Qt, Zero Web Runtime (WRT)**, and zero external dependencies.
- **Belle FP2 Interface**: High-performance double-buffered canvas, frosted glass status bar, sliding action sheets, and capacitive bottom pill toolbar (`Options`, `Gallery`, `Browse`, `Upload`).
- **Photo Gallery**: Native `RFs` directory scanner discovering JPEG, PNG, and GIF images across `E:\Images\`, `C:\Data\Images\`, `F:\Images\`, `E:\DCIM\`.
- **Dual File Pickers**: Direct Symbian drive tree explorer (`C: Phone`, `E: Mass Storage`, `F: Memory Card`) and system query picker.
- **In-App Menus (English)**: Full views for Terms of Service, Privacy Policy, and About Nullhost.

### Series 60 (S60 Keypad) — Native C++
- **100% Native Symbian C++**: Standalone Symbian C++ application compiled directly against S60 3rd/5th Edition ROM libraries. Zero external dependencies, zero Qt, zero WRT.
- **Keypad Ergonomics**: Hardware CBA softkeys (`Options` / `Back`) and 5-way D-Pad tab switcher (`[ Upload ] [ Browse ] [ Gallery ] [ Account ]`) with cursor highlight bar and number shortcuts 1-4.
- **Dual File Pickers**: D-Pad drive browser for `C:\`, `E:\`, `F:\` and system file query dialog.

### Universal Java ME MIDlet
- **Universal J2ME**: Runs on standard numeric keypad phones and resistive/capacitive touchscreens.
- **JSR-75 FileConnection**: File system browser across phone memory and removable storage.
- **Self-Contained Crypto**: Pure Java CLDC 1.1 HMAC-SHA256 implementation without external libraries.

### Windows Desktop Client
- **System Tray & Explorer Integration**: Right-click any file in Windows Explorer and choose "Upload to Nullhost".
- **Chunked Upload Engine**: Streams files larger than 95 MB in chunks to bypass proxy upload limits.

---

## Mobile Application API

Genuine client applications authenticate with the server via an HMAC-SHA256 handshake:

1. **Authentication Handshake (`POST /api/mobile/auth`)**:
   ```json
   {
     "client": "symbian-belle-fp2",
     "version": "1.0.0",
     "timestamp": 1729519200,
     "nonce": "4a8c9b2e1f3d0a",
     "signature": "hmac_sha256(key, 'symbian-belle-fp2:1729519200:4a8c9b2e1f3d0a')"
   }
   ```
2. **Server Response**:
   Returns session token and synchronized server timestamp.
3. **Upload (`POST /api/mobile/upload`)**:
   Standard multipart file upload passing `X-Nullhost-App-Token: <token>`, safely bypassing browser CAPTCHAs.

---

## License

This project is licensed under the [MIT License](LICENSE).
