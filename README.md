# Nullhost

Fast, anonymous, ephemeral and permanent file & text sharing server with standalone native client applications for modern desktop and vintage mobile platforms. Written in high-performance Go (Golang).

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Go 1.23+](https://img.shields.io/badge/Go-1.23%2B-00ADD8.svg?logo=go)](https://golang.org)
[![Java ME](https://img.shields.io/badge/Java%20ME-MIDP%202.0%20%2F%20JSR--75-orange.svg)](#universal-mobile-client-nokia-symbian-belle-fp2--s60--java-me)
[![Windows](https://img.shields.io/badge/Windows-.NET%204.5%2B-blue.svg)](#windows-desktop-client)

---

## Highlights

- **High-Performance Go Micro-service**: Ultra-low memory footprint (~2.5 MB RAM vs 120+ MB with Python/Gunicorn), sub-millisecond response latency, and massive concurrent connection handling.
- **Permanent Link Retirement**: Once an item is deleted, expired, nuked, or taken down, its shortcode is permanently recorded in `retired_ids` and will never be reassigned or reused.
- **Files & Pastes**: Share large files (up to 10 GB) or quick raw text snippets from the same lightweight web interface or native apps.
- **Ephemeral Expiration**: Choose retention periods (1 Hour, 24 Hours, 7 Days, 30 Days, or Never Expire).
- **Burn After Reading**: Files can self-destruct automatically after 1, 5, or 10 views.
- **Privacy & Encryption**: Password-protect uploads and collections. Automatic image EXIF and camera metadata scrubbing.
- **Collections & Folders**: Organize multiple files into shareable collections or download them directly as a streaming ZIP archive.
- **Turnstile Bot Protection**: Optional Cloudflare Turnstile integration that can be toggled on/off in `.env`.
- **Authentic Mobile API**: Cryptographic HMAC handshake allows verified native mobile clients (Nokia Symbian / Java ME) to authenticate and upload securely.

---

## Self-Hosting Guide

### 1. Requirements
- Linux / macOS / Windows server
- Go (Golang) 1.23+
- Reverse proxy (Caddy, Nginx, or Cloudflare Tunnel)

### 2. Quick Setup

```bash
# Clone repository
git clone https://github.com/Gelaxiz/nullhost.git
cd nullhost

# Configure environment
cp .env.example .env

# Compile the standalone binary
go build -o nullhost cmd/nullhost/main.go

# Run the server
./nullhost
```

### 3. Configuration (`.env`)

Nullhost works out-of-the-box with sensible defaults:

```ini
# Server host & port
HOST=127.0.0.1
PORT=4443

# Public domain & branding
SITE_DOMAIN=nullhost.gelaxiz.xyz
SITE_NAME=Nullhost

# Administration & 2FA
ADMIN_PASSWORD=YourAdminPassword
ADMIN_TOTP_SECRET=YourBase32TOTPSecret

# Cloudflare Turnstile (Optional - leave blank to disable)
TURNSTILE_SITE_KEY=
TURNSTILE_SECRET=

# Secret key for genuine mobile applications (HMAC authentication)
NULLHOST_MOBILE_APP_KEY=your_secret_mobile_key
```

### 4. Systemd Service Setup

To run Nullhost as a persistent background service:

```ini
# /etc/systemd/system/nullhost.service or ~/.config/systemd/user/nullhost.service
[Unit]
Description=Nullhost High-Performance Go Service
After=network.target

[Service]
Type=simple
WorkingDirectory=/home/projects/nullhost
Environment=PORT=4443
ExecStart=/home/projects/nullhost/nullhost
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

```bash
systemctl --user enable --now nullhost.service
```

---

## Universal Mobile Client (Nokia Symbian Belle FP2 / S60 / Java ME)

A dedicated native client is available in `client/j2me`:
- Built for physical Nokia devices (e.g. 808 PureView, E71) running Java ME MIDP 2.0 / CLDC 1.1.
- Compliant with CLDC 1.1 preverified stack maps.
- Direct cryptographic HMAC challenge-response upload flow without browser popups.

---

## Windows Desktop Client

A lightweight native desktop uploader built with C# and .NET 4.5+ is available in `client/windows/`.

---

## License

MIT License. See [LICENSE](LICENSE) for details.
