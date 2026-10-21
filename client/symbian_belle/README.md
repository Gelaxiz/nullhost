# Nullhost for Symbian ^3 / Belle FP2 (Native C++)

Pure standalone native Symbian C++ application for Symbian ^3, Symbian Anna, Nokia Belle, and Belle Feature Pack 2 (FP2) capacitive touch devices (e.g. Nokia 808 PureView, 701, 700, 603, N8, C7, E7, C6-01).

## Architecture: 100% Native Symbian C++
- **Zero Dependencies**: Pure Symbian OS application written in standard Symbian C++. **NO Qt, NO Qt Mobility, and NO Web Runtime (WRT)** required. Runs directly against Symbian OS ROM libraries (`euser.lib`, `apparc.lib`, `cone.lib`, `eikcore.lib`, `avkon.lib`, `gdi.lib`, `fbscli.lib`, `efsrv.lib`, `estor.lib`, `http.lib`).
- **Modern Belle FP2 UI (`CNullhostBelleContainer`)**:
  - High-performance double-buffered canvas (`CFbsBitmap`, `CFbsBitGc`).
  - Frosted glass status bar with digital clock, cellular signal, and battery meter.
  - Belle FP2 bottom toolbar with 4 large capacitive touch buttons (`Options`, `Gallery`, `Browse`, `Upload`).
  - Sliding action sheets with rounded corners for setting expiration, burn limits, password protection, and custom slug.
- **Visual Photo Gallery (`CNullhostGalleryScanner`)**: Recursive scanner for picture directories (`E:\Images\`, `C:\Data\Images\`, `F:\Images\`, `E:\DCIM\`) discovering JPEG, PNG, and GIF images with metadata.
- **Dual File Pickers (`CNullhostFileBrowser`)**:
  - Direct Symbian file system drive browser (`C: Phone Memory`, `E: Mass Storage`, `F: Memory Card`).
  - Native Symbian system query.
- **Authentic App Security (`TNullhostCrypto` & `CNullhostHttpEngine`)**:
  - Pure C++ HMAC-SHA256 implementation with zero external dependencies.
  - Active Object (`CActive`) asynchronous multipart HTTP upload engine.
- **In-App Menus (English Only)**: Dedicated scrollable views for Terms of Service, Privacy Policy, and About Nullhost.

## Project Structure
```
client/symbian_belle/
├── group/
│   ├── bld.inf              # Standard Symbian component build file
│   └── NullhostBelle.mmp    # Project definition linking Symbian OS libraries
├── inc/
│   ├── NullhostBelle.hrh    # Command and control IDs
│   ├── NullhostBelle.loc    # English localization strings
│   ├── NullhostBelleAppUi.h
│   ├── NullhostBelleApplication.h
│   ├── NullhostBelleDocument.h
│   ├── NullhostBelleContainer.h
│   ├── NullhostCrypto.h     # C++ HMAC-SHA256 crypto header
│   ├── NullhostFileBrowser.h # Symbian RFs drive browser
│   ├── NullhostGalleryScanner.h # Photo scanner
│   └── NullhostHttpEngine.h # Active Object HTTP engine
├── src/
│   ├── NullhostBelleMain.cpp # E32Main() entry point
│   ├── NullhostBelleAppUi.cpp
│   ├── NullhostBelleApplication.cpp
│   ├── NullhostBelleDocument.cpp
│   ├── NullhostBelleContainer.cpp
│   ├── NullhostCrypto.cpp
│   ├── NullhostFileBrowser.cpp
│   ├── NullhostGalleryScanner.cpp
│   └── NullhostHttpEngine.cpp
├── data/
│   ├── NullhostBelle.rss     # Symbian UI resource definitions
│   └── NullhostBelle_reg.rss # App registration file (UID 0xE009F810)
└── NullhostBelle.pkg         # SISX installer specification
```

## Building with Symbian C++ SDK
Using standard Symbian C++ command-line tools:
```cmd
cd group
bldmake bldfiles
abld build armv5 urel
makesis ..\NullhostBelle.pkg ..\Nullhost_Belle.sis
signsis ..\Nullhost_Belle.sis ..\Nullhost_Belle.sisx mycert.cer mykey.key
```
Output: `Nullhost_Belle.sisx`
