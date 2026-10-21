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
| **Symbian ^3 (Belle FP2)** | Symbian Belle FP2 (Nokia 808, 701, N8, etc.) | `client/Nullhost_Belle.sisx` | `client/symbian_belle/` |
| **Series 60 Keypad** | S60 3rd / 5th Edition Keypad (Nokia E52, E72, N95, etc.) | `client/Nullhost_S60.sis` | `client/s60_keypad/` |
| **Universal Mobile (.JAR)** | Symbian, S60, Series 40, Java ME | `client/Nullhost.jar` & `Nullhost.jad` | `client/j2me/src/` |
| **Windows Desktop** | Windows 7 / 8 / 10 / 11 (.NET 4.5+) | `client/Nullhost.exe` | `client/windows/` |
| **Belle C++ Source** | Pure Native Symbian C++ Source | `Nullhost_Symbian_Belle_Source.zip` | `client/symbian_belle/` |
| **S60 C++ Source** | Pure Native Symbian C++ Source | `Nullhost_S60_Keypad_Source.zip` | `client/s60_keypad/` |

### Universal Mobile Client (Nokia Symbian Belle FP2 / S60 / Java ME)
- **Universal Zero-Dependency Standalone Application**: Fully compatible out-of-the-box with Nokia Symbian Belle FP2 (Nokia 808 PureView, 701, 700, 603), Symbian^3 / Anna (N8, E7, C7), S60 keypad devices, and Series 40 via the hardware-accelerated Nokia JVM in ROM.
- **Strictly Zero Python**: Zero Python runtime, zero PyS60 scripts, zero external dependencies. Never encounters launcher crashes.
- **Authentic Branding**: Official Nullhost logo embedded directly (`icon.png` / `MIDlet-Icon`) in high-resolution RGBA.
- **JSR-75 Native Filesystem Explorer**: Complete file browsing across phone memory (`C:`), mass storage (`E:`), and memory card (`F:`).
- **HMAC Handshake & Multipart Upload**: Secure HMAC-SHA256 authentication and direct upload engine.

### Symbian Native C++ Source Trees
- **100% Native Symbian C++**: Pure C++ implementations (`inc/`, `src/`, `group/`, `data/`) using standard Symbian OS ROM libraries. **Zero Qt, Zero Web Runtime (WRT)**.
- **Belle FP2 Interface**: High-performance double-buffered canvas, frosted glass status bar, sliding action sheets, and capacitive bottom pill toolbar (`Options`, `Gallery`, `Browse`, `Upload`).
- **S60 Keypad Ergonomics**: Hardware CBA softkeys (`Options` / `Back`) and 5-way D-Pad tab navigation.
- **Ready for Carbide.c++ & Nokia SDK**: Packaged as clean source zips with `bld.inf`, `.mmp`, `.pkg`, and resource definition files.

### Windows Desktop Client
- **System Tray & Explorer Integration**: Right-click any file in Windows Explorer and choose "Upload to Nullhost".
- **Authentic Logo**: Official multi-resolution `logo.ico` embedded directly into the PE executable.
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
