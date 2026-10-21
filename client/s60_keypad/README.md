# Nullhost for Series 60 (S60 Keypad Devices - Native C++)

Pure standalone native Symbian C++ application designed specifically for classic non-touch keypad devices running S60 3rd Edition and 5th Edition (e.g. Nokia N95, E71, E72, E51, E52, N82, 6120 classic, C5-00).

## Architecture: 100% Native Symbian C++
- **Zero Dependencies**: Pure Symbian OS application written in standard Symbian C++. **NO Qt, NO Web Runtime (WRT)** required. Runs directly against Symbian OS ROM libraries.
- **Hardware Ergonomics (`CNullhostS60Container`)**:
  - Handles keypad events via `OfferKeyEventL()`.
  - CBA softkeys (Left Softkey: `Options`, Right Softkey: `Back` / `Exit`).
  - 5-Way D-Pad navigation:
    - Up / Down: scroll cursor highlight bar through items.
    - Left / Right: tab switcher (`[ Upload ] [ Browse ] [ Gallery ] [ Account ]`).
    - Center Key (`EKeyDevice3`): Select / Activate.
  - Number shortcuts: Keys `1` through `4` switch tabs directly.
- **Dual File Pickers (`CNullhostFileBrowser`)**:
  - D-Pad tree navigation for drives `C:\`, `E:\`, and `F:\`.
  - System file query dialog.
- **Local Photo Gallery (`CNullhostGalleryScanner`)**: Scans storage volumes for images with metadata.
- **Authentic App Security (`TNullhostCrypto` & `CNullhostHttpEngine`)**:
  - Pure C++ HMAC-SHA256 handshake token generation.
  - Asynchronous Active Object multipart upload.
- **In-App Menus (English Only)**: Dedicated scrollable views for Terms of Service, Privacy Policy, About, and Settings.

## Project Structure
```
client/s60_keypad/
├── group/
│   ├── bld.inf              # Standard Symbian component build file
│   └── NullhostS60.mmp      # Project definition linking Symbian OS libraries
├── inc/
│   ├── NullhostS60.hrh      # Command and tab IDs
│   ├── NullhostS60.loc      # English localization strings
│   ├── NullhostS60AppUi.h
│   ├── NullhostS60Application.h
│   ├── NullhostS60Document.h
│   ├── NullhostS60Container.h
│   ├── NullhostCrypto.h     # C++ HMAC-SHA256 crypto header
│   ├── NullhostFileBrowser.h # Symbian RFs drive browser
│   ├── NullhostGalleryScanner.h # Photo scanner
│   └── NullhostHttpEngine.h # Active Object HTTP engine
├── src/
│   ├── NullhostS60Main.cpp   # E32Main() entry point
│   ├── NullhostS60AppUi.cpp
│   ├── NullhostS60Application.cpp
│   ├── NullhostS60Document.cpp
│   ├── NullhostS60Container.cpp
│   ├── NullhostCrypto.cpp
│   ├── NullhostFileBrowser.cpp
│   ├── NullhostGalleryScanner.cpp
│   └── NullhostHttpEngine.cpp
├── data/
│   ├── NullhostS60.rss       # Symbian UI resource definitions
│   └── NullhostS60_reg.rss   # App registration file (UID 0xE009F811)
└── NullhostS60.pkg           # SIS installer specification
```

## Building with Symbian C++ SDK
Using standard S60 3rd/5th Edition SDK command-line tools:
```cmd
cd group
bldmake bldfiles
abld build armv5 urel
makesis ..\NullhostS60.pkg ..\Nullhost_S60.sis
signsis ..\Nullhost_S60.sis ..\Nullhost_S60.sis mycert.cer mykey.key
```
Output: `Nullhost_S60.sis`
