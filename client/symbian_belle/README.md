# Nullhost for Symbian ^3 / Belle FP2

Standalone native application for Symbian ^3, Symbian Anna, Nokia Belle, and Belle Feature Pack 2 (FP2) capacitive touch devices (e.g. Nokia 808 PureView, 701, 700, 603, N8, C7, E7, C6-01).

## Features
- **100% Standalone Native**: Pure Symbian OS 9.x application. **NO Qt, NO Qt Mobility, NO Web Runtime (WRT)** required.
- **Modern Belle FP2 UI**: Dark frosted glass status bar with digital clock, signal & battery meter, sliding action sheets with rounded corners, and bottom touch action bar (`Options`, `Gallery`, `Browse`, `Upload`).
- **Visual Photo Gallery**: Automatic scanner for `E:\Images\`, `C:\Data\Images\`, `F:\Images\`, `E:\DCIM\` with thumbnail details and direct photo upload.
- **Dual File Pickers**:
  - In-app drive and folder explorer (`C: Phone Memory`, `E: Mass Storage`, `F: Memory Card`).
  - Native Symbian system file query.
- **Upload Options**:
  - Expiry: 1 Hour, 24 Hours (default), 7 Days, 30 Days, Never Expire.
  - Burn After Reading: Disabled, 1 View, 5 Views, 10 Views.
  - Password protection.
  - Custom URL slug.
  - 24-digit Account ID integration.
- **Authentic App Security**: Cryptographic HMAC-SHA256 handshake token (`/api/mobile/auth`) allowing uploads to bypass web Turnstile CAPTCHA.
- **In-App Menu Sections**:
  - Terms of Service
  - Privacy Policy
  - About Nullhost (with local drive storage status)

## Packaging
The standalone SISX package is built using [Ensymble-NG](https://github.com/Gelaxiz/nullhost):
```bash
python3 tools/package_apps.py
```
Output: `client/Nullhost_Belle.sisx`
