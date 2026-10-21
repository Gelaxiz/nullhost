# Nullhost for Series 60 (S60 Keypad Devices)

Standalone native application designed for classic non-touch keypad devices running S60 3rd Edition and 5th Edition (e.g. Nokia N95, E71, E72, E51, E52, N82, 6120 classic, C5-00).

## Features
- **Keypad Ergonomics**: Hardware CBA softkeys (Left Softkey: `Options`, Right Softkey: `Back` / `Exit`) and 5-way D-Pad navigation.
- **Top Tab Switcher**: Quickly switch between `[ Upload ]`, `[ Browse ]`, `[ Gallery ]`, and `[ Account ]` using D-Pad Left/Right or number shortcuts (1-4).
- **100% Standalone Native**: Pure Symbian OS 9.x application. **NO Qt, NO Web Runtime (WRT)** required.
- **Dual File Pickers**:
  - In-app drive tree explorer (`C:\`, `E:\`, `F:\`).
  - Native Symbian system file picker.
- **Local Photo Gallery**: Scans device memory and memory card for JPEG, PNG, GIF files with metadata.
- **Upload Options**:
  - Expiry: 1h, 24h, 7d, 30d, never.
  - Burn views: Off, 1 View, 5 Views, 10 Views.
  - Password protection.
  - Custom link slug.
  - 24-digit Account ID support.
- **Authentic App Security**: Authenticates via HMAC-SHA256 handshake token (`/api/mobile/auth`).
- **In-App Menu Sections**:
  - Terms of Service
  - Privacy Policy
  - About Nullhost
  - Settings (Server URL, Account ID)

## Packaging
The standalone SIS package is built using [Ensymble-NG](https://github.com/Gelaxiz/nullhost):
```bash
python3 tools/package_apps.py
```
Output: `client/Nullhost_S60.sis`
