# Gelaxiz Ecosystem & Server Master Agent Handoff Specification

**Last Updated**: 2026-11-01 23:25 (Europe/Helsinki)  
**Environment**: Ubuntu Linux x86_64 (Kernel 6.8), systemd user & system services  
**Network Architecture**: Ingress through Cloudflare Zero Trust Tunnel; local loopback isolation; zero open inbound WAN ports.

---

## 1. Global Ecosystem Architecture & Port Registry

Authoritative port configuration is maintained in `kaytetyt_portit.txt` and on the Desktop. All public services resolve through Cloudflare Tunnel to local binding ports.

| Port | Protocol / Bind | Public Hostname | Service / Path | Description & Technology |
|---|---|---|---|---|
| **4443** | TCP 127.0.0.1 | `nullhost.gelaxiz.xyz`<br>`nullhost.cc` | `nullhost.service`<br>`/home/projects/nullhost` | Nullhost v2.0 (High-Performance Go micro-service, Chi v5, Gonja Jinja2 template rendering, SQLite WAL mode, J2ME / Symbian client API, ~17 MB RAM) |
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
- **Grok CLI**: Completely removed from host (`~/.grok`, `~/.local/bin/grok`, bashrc).
- **Reserved Range**: Ports `4970` and `4987–5001` are permanently reserved.
- **Next Free Default Port**: `5004` (allocate in range `5004–5099`).

---

## 2. Nullhost Core Architecture (v2.0 Go Backend)

Nullhost has been completely migrated to a pure Go micro-service architecture (`/home/projects/nullhost`), deprecating and eradicating all legacy Python/Flask code.

### A. Pure Go Architecture & Zero Python Residue
- **Entry Point**: `cmd/nullhost/main.go`
- **Internal Modules**:
  - `internal/database/`: SQLite WAL mode with `modernc.org/sqlite` pure-Go driver, connection pooling, collision-free `GenerateID`, and permanent `retired_ids` guarantee.
  - `internal/auth/`: Dual PBKDF2/scrypt password hashing (backwards-compatible with legacy hashes), RFC 6238 TOTP verification with ±60s clock drift tolerance, dual parameter parsing (`totp_code` and `totp`), signed HMAC-SHA256 session tokens.
  - `internal/storage/`: Atomic uploads, directory traversal defense, EXIF/metadata scrubbing, collection streaming ZIP generator.
  - `internal/cleanup/`: Background ticker (10-minute pass) pruning expired items, removing disk files, and retiring shortcodes.
  - `internal/templates/`: Jinja2 template rendering via Gonja v2 with registered `tojson` and `url_for` filters and thread-safe buffer pooling.
  - `internal/handlers/`: Modular Chi v5 HTTP routers (`upload.go`, `download.go`, `collections.go`, `accounts.go`, `admin.go`, `mobile_api.go`, `web.go`).
- **Memory Footprint**: ~17 MB RAM (vs >100 MB under Python Gunicorn workers).
- **Unit Testing**: Native Go test suite (`go test -v ./...`) covering all packages with 100% pass rate.

### B. Permanent Link Retirement (Zero-Reuse Guarantee)
- Table: `retired_ids (id TEXT PRIMARY KEY)` in `nullhost.db`.
- **Zero Metadata**: Strictly only the `id` column is stored; zero filenames, file hashes, timestamps, or account IDs are retained.
- **All Deletion Paths Enforced**: Expired cleanup worker, anonymous deletions, user dashboard deletions, collection deletions, bulk removals, account nuke, burn-after-read auto-delete, and admin takedowns/bans permanently retire the shortcode.
- **Random ID Generation**: Queries `retired_ids` during generation to ensure newly minted IDs never collide with retired IDs.
- **Real-Time Validation Endpoint**: `GET /api/check_id/<id>` returns `{ status: 'available' | 'in_use' | 'retired' | 'reserved' | 'invalid' }`.

### C. Admin Dashboard & Authentication Security
- **6FA Gate**: Cloudflare Turnstile bot verification + TOTP (6-digit authenticator code) + configurable `ADMIN_PASSWORD`.
- **Clock Drift Protection**: TOTP window set to ±60 seconds to eliminate synchronization failures from client phone clocks.
- **Form Compatibility**: Handles both `totp_code` and `totp` fields transparently.
- **Dark-Themed Error Pages**: Failed logins and cooldown states render full dark-themed HTML views (`admin_login.html`) with countdown timers instead of raw `text/plain` errors.
- **Instant Debounced Search**: Admin dashboard (`#admin-search-input`) triggers instantaneous client-side DOM row filtering, followed by debounced (~150ms) background fetch to `GET /admin_dashboard?q=...&format=json`.
- **Real-Time Storage Calculation**: Admin stats calculate and display `total_size_mb` (e.g., `600.63 MB STORED`).
- **AJAX Actions (Zero Page Reload)**: Deletions, takedowns/bans, quarantine reason updates, tombstone revocations, and manual cleanup execute via asynchronous fetch requests with smooth CSS transitions and dynamic stat counters.
- **Logout**: Route `/admin/logout` cleanly clears session cookies and returns HTTP 302 redirect back to `/admin`.

### D. Layout Standards & Anti-Stretching UI Polish
- **Container Geometry**: Layout containers (`.nav`, `.content`) strictly use `max-width: 1160px; margin: 0 auto; padding: 28px 24px 80px;` (expanded to `1220px` at `min-width: 1800px`).
- **Anti-Stretching Rules**: Form inputs, action bars, and tables are constrained inside readable card containers instead of spanning full-screen widths across 1080p, 1440p, or 4K monitors.
- **Stat Cards Grid**: Admin metrics displayed in a structured 6-column card grid (`repeat(6, 1fr); gap: 12px;`) that scales down to 3 columns on tablets (`<=960px`) and 2 columns on mobile (`<=560px`).
- **Header Design**: Top navigation bar features streamlined logo-only branding, eliminating redundant text labels.
- **Cache-Busting Versioning**: `v='20261101-layout-polish'` on stylesheets and assets.

### E. Complete Route Coverage (Zero 404s)
- **Search**: `GET /search` (routes `!admin` shortcut or redirects directly to `/c/{id}` or `/v/{id}`).
- **Item Actions**: `/delete/{id}`, `/rename/{id}`, `/items/delete-selected`, `/api/items/delete-uploaded`.
- **Collections**: Complete suite implemented (`/collections/init_batch`, `/collections/auto`, `/collections/{id}/name`, `/collections/{id}/items`, `/collections/{id}/items/move`, `/collections/{id}/items/remove-selected`, `/collections/{id}/items/{item_id}/rename`, `/collections/{id}/items/{item_id}/remove`, `/collections/{id}/settings`, `/collections/{id}/delete`).
- **QR Codes**: `/c/{id}/qr.png` and `/v/{id}/qr.png` generated dynamically on-the-fly via `github.com/skip2/go-qrcode`.
- **Mobile API**: `/api/mobile/public`, `/api/mobile/item/{id}`, `/api/mobile/preview/{id}`.

### F. J2ME & Symbian Hardware Constraints
- **Hardware Clients**: Physical Nokia 808 PureView (Belle FP2) and Nokia E71 (S60 3rd Ed) running Java ME (MIDP 2.0 / CLDC 1.1).
- **CLDC 1.1 StackMap Bytecode**: S60 3rd Edition KVM will throw `java.lang.VerifyError` if class files lack CLDC 1.1 stack maps. Compilation MUST be run via `client/j2me/build.sh` with ProGuard 6.2.2 `-microedition -target 1.4`.
- **Permissions**: Use `MIDlet-Permissions-Opt` in `Manifest.mf` and `Nullhost.jad` so unsigned builds execute without `SecurityException`.
- **Legacy SSL Bypass**: Nokia E71 only supports SSLv3 / TLS 1.0. Modern Cloudflare TLS 1.3 blocks it. `IsLegacyBrowser()` in Go backend exempts `/api/` and `Nullhost-J2ME` clients from HTTP-to-HTTPS redirect enforcement.

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
# Nullhost unit tests (Go test suite)
cd /home/projects/nullhost && go test -v ./...

# Build Nullhost Go binary
cd /home/projects/nullhost && go build -ldflags="-s -w" -trimpath -o nullhost cmd/nullhost/main.go

# Restart Nullhost service
systemctl --user restart nullhost.service

# Check Nullhost service status & logs
systemctl --user status nullhost.service --no-pager
journalctl --user -u nullhost.service -n 100 --no-pager

# Build & restart Docs App
cd /home/projects/docs-app && npm run build
systemctl --user restart docs-app.service

# Recompile J2ME mobile client
cd /home/projects/nullhost/client/j2me && ./build.sh

# Run source directory backup
/home/niilo/paivita-backupit.sh
```
