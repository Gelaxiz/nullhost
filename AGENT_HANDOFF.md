# Gelaxiz Ecosystem & Server Master Agent Handoff Specification

**Last Updated**: 2026-11-01  
**Environment**: Ubuntu Linux x86_64 (Kernel 6.8), systemd user & system services  
**Network Architecture**: Ingress through Cloudflare Zero Trust Tunnel; local loopback isolation; zero open inbound WAN ports.

---

## 1. Global Ecosystem Architecture & Port Registry

Authoritative port configuration is maintained in `kaytetyt_portit.txt` and on the Desktop. All public services resolve through Cloudflare Tunnel to local binding ports.

| Port | Protocol / Bind | Public Hostname | Service / Path | Description & Technology |
|---|---|---|---|---|
| **4443** | TCP 127.0.0.1 | `nullhost.gelaxiz.xyz`<br>`nullhost.cc` | `nullhost.service`<br>`/home/projects/nullhost` | Nullhost v2.0 (High-Performance Go micro-service, Chi v5, Gonja Jinja2 template rendering, SQLite WAL mode, J2ME / Symbian client API, ~5 MB RAM) |
| **4970** | TCP 127.0.0.1 | *Internal only* | `ytdlp-web.service` | Outbound media proxy for YT-DLP downloader |
| **4989** | TCP 127.0.0.1 | `yt.gelaxiz.xyz` | `ytdlp-web.service`<br>`/home/projects/ytdlp-web-app` | YouTube and media stream downloader web interface |
| **4991** | TCP 127.0.0.1 | `abc.gelaxiz.xyz` | `abc.service`<br>`/home/projects/abc` | ABC Retro Media & retro nostalgia platform |
| **4992** | TCP * | `chat.gelaxiz.xyz` | `sharkord.service`<br>`/home/projects/sharkord` | Sharkord persistent communication & chat server |
| **4993** | TCP 127.0.0.1 | `files.gelaxiz.xyz` | `filebrowser` binary | Standalone web file browser for personal storage |
| **4994** | TCP * | `rawfiles.gelaxiz.xyz` | Nginx HTTP server | Direct raw directory browser & static file downloads |
| **4995** | TCP * | *Internal / Nginx* | Nginx + PHP-FPM | _h5ai cached visual file directory index |
| **4996** | TCP 127.0.0.1 | `docs.gelaxiz.xyz` | `docs-app.service`<br>`/home/projects/docs-app` | Gelaxiz Documentation Platform (Next.js 16, Turbopack, Turnstile + Password + 2FA TOTP gate) |
| **4997** | TCP * | `radio.gelaxiz.xyz` | Icecast relay | Radio loop and legacy relay stream distribution |
| **4998** | TCP 127.0.0.1 | `games.gelaxiz.xyz` | `games-website.service`<br>`/home/projects/games-website` | Gelaxiz Games web arcade & multiplayer hub |
| **5000** | TCP * | `song.gelaxiz.xyz` | `song-guesser.service`<br>`/home/projects/song-guesser` | First Drop music recognition and quiz application |
| **5001** | TCP 127.0.0.1 | `dogeub.gelaxiz.xyz` | `dogeub.service`<br>`/home/projects/dogeub` | DogeUB web proxy & internet utility hub |
| **7070** | TCP * | `radio.gelaxiz.xyz` | `icecast2.service` | Icecast2 audio streaming server (HQ 256k MP3 streams) |
| **25565**| TCP * | *Direct LAN/WAN* | Minecraft daemon | Eaglercraft / Paper Minecraft game server |
| **30814**| UDP/TCP | *Direct LAN/WAN* | BeamMP daemon | BeamNG.drive multiplayer server |
| **39999**| TCP+UDP | `chat.gelaxiz.xyz` | Sharkord mediasoup | WebRTC voice/video media relay worker |

### Decommissioned / Retired Services (Do NOT Re-use Ports)
- **Fluxer** (Ports `4445`, `7881`, `7882`): Completely removed.
- **Nexus School / Nequz** (Port `4990`): Completely removed.
- **Obsidian App**: Completely uninstalled; desktop shortcuts and vault removed.
- **Reserved Range**: Ports `4970` and `4987–5001` are permanently reserved.
- **Next Free Default Port**: `5004` (allocate in range `5004–5099`).

---

## 2. Nullhost Core Architecture (v1.9.9)

### A. Permanent Link Retirement (Zero-Reuse Guarantee)
- Table: `retired_ids (id TEXT PRIMARY KEY)` in `nullhost.db`.
- **Zero Metadata**: Strictly only the `id` column is stored; zero filenames, file hashes, timestamps, or account IDs are retained.
- **All Deletion Paths Enforced**: Expired cleanup worker, anonymous deletions, user dashboard deletions, collection deletions, bulk removals, account nuke, burn-after-read auto-delete, and admin takedowns/bans permanently retire the shortcode.
- **Random ID Generation**: Queries `retired_ids` during generation to ensure newly minted IDs never collide with retired IDs.
- **Real-Time Validation Endpoint**: `GET /api/check_id/<id>` returns `{ status: 'available' | 'in_use' | 'retired' | 'reserved' | 'invalid' }`.

### B. Admin Dashboard UX & Instant Search
- **Instant Debounced Search**: Admin dashboard (`#admin-search-input`) triggers instantaneous client-side DOM row filtering, followed by debounced (~150ms) background fetch to `GET /admin_dashboard?q=...&format=json`. Results populate dynamically without reloading the page.
- **AJAX Actions (Zero Page Reload)**: Deletions, takedowns/bans, quarantine reason updates, tombstone revocations, and manual cleanup execute via asynchronous fetch requests with smooth CSS transitions, toast notifications, and dynamic stat counters.
- **Non-Permanent Session Security**: Admin cookies are memory-only (`session.permanent = False`). Page-level `sessionStorage` and `navigator.sendBeacon('/admin/logout')` immediately terminate admin credentials when the admin browser tab is closed.
- **Authentication**: Protected by 6FA gate: Cloudflare Turnstile bot verification + TOTP (6-digit authenticator code) + configurable `ADMIN_PASSWORD` (`Syyskuu_05`).

### C. Responsive Widescreen Layout
- Layout containers (`.nav`, `.content`) utilize fluid widescreen geometry: `width: 100%; max-width: 96vw; margin: 0 auto;`.
- Tables, stat grids, and action panels scale smoothly up to 4K displays without being trapped in narrow fixed-width columns.
- Cache-busting version strings (`v='20261101-fluid-widescreen'`) are applied to both `style.css` and `ecosystem.css`.

### D. Password Manager & Autofill Defense
- All non-credential text inputs (`custom_id`, `custom_filename`, `folder_path`, search fields) carry explicit attributes:
  `autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" data-lpignore="true" data-1p-ignore="true" data-form-type="other"`.
- Passphrase inputs for uploads and collections carry `autocomplete="new-password"` to prevent browser password managers (Firefox Lockwise, Chrome Autofill, 1Password) from falsely treating custom link fields as login usernames.

### E. Database Performance
- SQLite database runs in Write-Ahead Logging mode (`PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;`). This eliminates rollback journal locks and prevents `.db-journal` temporary file residue.

### F. J2ME & Symbian Hardware Constraints
- **STRICT RULE**: NO Python on client mobile devices. Clients are physical Nokia 808 PureView (Belle FP2) and Nokia E71 (S60 3rd Ed) running Java ME (MIDP 2.0 / CLDC 1.1).
- **CLDC 1.1 StackMap Bytecode**: S60 3rd Edition KVM will throw `java.lang.VerifyError` if class files lack CLDC 1.1 stack maps. Compilation MUST be run via `client/j2me/build.sh` with ProGuard 6.2.2 `-microedition -target 1.4`.
- **Permissions**: Use `MIDlet-Permissions-Opt` in `Manifest.mf` and `Nullhost.jad` so unsigned builds execute without `SecurityException`.
- **Legacy SSL Bypass**: Nokia E71 only supports SSLv3 / TLS 1.0. Modern Cloudflare TLS 1.3 blocks it. `is_legacy_browser()` in `app.py` exempts `/api/` and `Nullhost-J2ME` clients from HTTP-to-HTTPS redirect enforcement.

### G. Nullhost High-Performance Go Backend
- **Location**: `/home/projects/nullhost` (compiled binary: `nullhost`)
- **Architecture**: Modular Go (Golang 1.23+) micro-architecture:
  - `cmd/nullhost/main.go`: Server bootstrap, WAL pragma setup, OS signal shutdown handling.
  - `internal/database/db.go`: SQLite WAL mode with modernc pure-Go driver, collision-free `GenerateID`, permanent `retired_ids` guarantee, transaction-safe takedowns.
  - `internal/auth/auth.go`: Dual PBKDF2/scrypt password hashing (matching Python Werkzeug hashes), timing-safe admin password validation, RFC 6238 TOTP verification, signed HMAC session tokens.
  - `internal/storage/storage.go`: Directory traversal prevention, safe filename/path sanitization, atomic EXIF/camera metadata scrubbing, collection streaming ZIP archive generation.
  - `internal/cleanup/worker.go`: Background ticker (10-minute pass) pruning expired uploads, orphaned files, and retiring all associated IDs.
  - `internal/templates/renderer.go`: Jinja2 template renderer with Gonja v2, macro `url_for` support, and buffer pooling.
  - `internal/handlers/`: Chi v5 HTTP router with modular routes (`upload.go`, `download.go`, `collections.go`, `accounts.go`, `admin.go`, `mobile_api.go`, `web.go`).
- **Compatibility**: 100% byte-for-byte and API compatible with the database schema and Nokia J2ME / Symbian client protocols.
- **Service**: Managed by systemd user service `nullhost.service` running directly on port `4443`.
- **Test Command**: `cd /home/projects/nullhost && go test -v ./...`
- **Build Command**: `cd /home/projects/nullhost && go build -ldflags="-s -w" -trimpath -o nullhost cmd/nullhost/main.go`

---

## 3. Documentation Platform (Docs App)

- **Location**: `/home/projects/docs-app` (`docs-app.service`, port `4996`).
- **Stack**: Next.js 16 (Turbopack), Tailwind CSS, React 19.
- **Security & Authentication**:
  - Protected at `/admin` with Cloudflare Turnstile, Admin Password (`Syyskuu_05`), and 6-digit TOTP 2FA.
  - Supports synchronized TOTP secrets (`VTAE42SXAHBHR3EZ`) matching the primary ecosystem admin authenticator.
  - Session cookie (`admin_auth`) with cryptographic HMAC-SHA256 signature and `sessionGeneration` invalidation.
- **Content Storage**: Markdown files stored in `docs-content/`, organized by category, rendered dynamically with sidebar navigation, emoji tags, and search.

---

## 4. Radio Streaming & Euro Truck Simulator 2 (ETS2)

- **Icecast Server**: `icecast2.service` running on port `7070` (`radio.gelaxiz.xyz`).
- **Stream Relay Daemons**:
  - `suomipop-stream.service` -> `suomipop.mp3` (HQ 256k)
  - `radiorock-stream.service` -> `radiorock.mp3` (HQ 256k)
  - `hitmix-stream.service` -> `hitmix.mp3` (HQ 256k)
  - `radio-stream.service` -> `loop.mp3` (HQ 256k)
  - `nova-stream.service` -> `nova.mp3`
  - `iskelma-stream.service` -> `iskelma.mp3`
  - `radio957-stream.service` -> `radio957.mp3`
  - `nrj-stream.service` -> `nrj.mp3`
  - `jr-stream.service` -> `jr.mp3`
- **Euro Truck Simulator 2 Integration**:
  - Master config file: `/home/niilo/live_streams.sii`.
  - Contains 14 curated high-quality Finnish stations directly playable inside ETS2 in-game radio player.
  - Automatically synchronized to backup storage.

---

## 5. Automated Backup Architecture

- **Destination**: `/mnt/myshare/Niilo/backup-sources/` (`//192.168.8.8/verkkolevy/Niilo/backup-sources`).
- **Storage Format**: **PLAIN DIRECTORY TREES ONLY** (No `.tar.gz` archives). Every project is stored as a direct, navigable folder.
- **Backup Script**: `/home/niilo/paivita-backupit.sh`.
- **Sync Mechanism**: Uses `rsync -av --delete` with smart exclusion rules:
  - Excludes: `node_modules`, `.next`, `dist`, `build`, `bin`, `obj`, `.git`, `.cache`, `venv`, `__pycache__`, `uploads`, heavy binary archives (`*.mp4`, `*.tar.gz`).
- **Included in Backups**:
  - All project directories (`nullhost/`, `docs-app/`, `games-website/`, `dogeub/`, `abc/`, `song-guesser/`, `ytdlp-web-app/`, `ente/`, `sharkord/`, `c64-emulator/`, `gelaxiz-xyz/`).
  - Radio stream units and scripts (`radio-streams/`).
  - ETS2 radio configuration (`live_streams.sii`).
  - Network and port mapping reference (`kaytetyt_portit.txt`).
  - Master handoff documentation (`AGENT_HANDOFF.md`).

---

## 6. Maintenance Commands Quick Reference

```bash
# Nullhost unit tests (47 tests passing)
cd /home/projects/nullhost && venv/bin/python3 -m unittest discover tests/ -v

# Restart Nullhost service
systemctl --user restart nullhost.service

# Build & restart Docs App
cd /home/projects/docs-app && npm run build
systemctl --user restart docs-app.service

# Recompile J2ME mobile client
cd /home/projects/nullhost/client/j2me && ./build.sh

# Run source directory backup
/home/niilo/paivita-backupit.sh
```
