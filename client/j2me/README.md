# Nullhost Universal Java ME (MIDlet)

Universal Java ME application compatible with virtually any mobile phone supporting MIDP 2.0 / CLDC 1.1 and JSR-75 (FileConnection API) — including Nokia Series 40, Symbian, Sony Ericsson, Samsung, Motorola, LG, and BlackBerry devices.

## Features
- **Universal Compatibility**: Pure Java ME bytecodes. Runs seamlessly on touchscreens and numeric keypads.
- **JSR-75 File System Access**: Browse device memory and external memory cards (`file:///c:/`, `file:///e:/`, `file:///root1/`, etc.).
- **Photo Gallery**: Automatic scanner discovering images across storage volumes.
- **Zero-Dependency Crypto**: Pure Java CLDC 1.1 implementation of SHA-256 and HMAC-SHA256. No `javax.crypto` needed.
- **Upload Options**: Expiry (1h, 24h, 7d, 30d, never), Burn limits (1, 5, 10 views), password protection, custom link slugs, and account ID.
- **Configurable Server**: Built-in settings form allowing connection to the official Nullhost server or any self-hosted instance.
- **In-App Menus**:
  - Terms of Service
  - Privacy Policy
  - About Nullhost (with live JVM heap memory and screen resolution display)

## Compilation & Packaging
Compiled against MIDP 2.0 and JSR-75 standard APIs:
```bash
mkdir -p build
javac -source 1.6 -target 1.6 -cp microemulator.jar:microemu-jsr-75.jar -d build src/*.java
jar cfm Nullhost.jar Manifest.mf -C build .
```
Output:
- `client/Nullhost.jar`
- `client/Nullhost.jad`
