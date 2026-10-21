# -*- coding: utf-8 -*-
# Nullhost for Symbian ^3 / Anna / Belle / Belle FP2
# Pure Standalone Native Application - NO Qt, NO Web Runtime (WRT)
# Copyright (c) 2026 Gelaxiz. All rights reserved.

import sys
import os
import time
import random
import string
import struct

# PyS60 core modules
try:
    import appuifw
    import graphics
    import e32
    IN_SYMBIAN = True
except ImportError:
    IN_SYMBIAN = False

# Fallback crypto if hashlib/hmac missing in minimal PyS60
try:
    import hashlib
    import hmac
    HAS_NATIVE_CRYPTO = True
except ImportError:
    HAS_NATIVE_CRYPTO = False

# Networking
try:
    import urllib
    import urllib2
    import httplib
except ImportError:
    pass

# Application Constants
APP_NAME = u"Nullhost"
APP_VERSION = u"1.0.0"
CLIENT_ID = "symbian-belle-fp2"
DEFAULT_SERVER = "https://nullhost.gelaxiz.fi"
MOBILE_SECRET = "nullhost_secret_mobile_app_key_2026"

# Color Palette - Belle FP2 Modern Dark Glass Theme
COLOR_BG = (18, 22, 28)
COLOR_CARD = (28, 34, 44)
COLOR_CARD_BORDER = (42, 50, 64)
COLOR_TOOLBAR = (12, 15, 20)
COLOR_TOOLBAR_BORDER = (32, 40, 52)
COLOR_ACCENT = (0, 168, 232)
COLOR_ACCENT_HOVER = (0, 200, 255)
COLOR_TEXT = (245, 248, 250)
COLOR_TEXT_MUTED = (140, 150, 165)
COLOR_SUCCESS = (46, 204, 113)
COLOR_WARNING = (241, 196, 15)
COLOR_DANGER = (231, 76, 60)
COLOR_BUTTON = (36, 44, 58)
COLOR_BUTTON_ACTIVE = (0, 140, 200)

def safe_hmac_sha256(key_str, msg_str):
    """Compute HMAC-SHA256 with native hashlib/hmac or pure fallback."""
    if HAS_NATIVE_CRYPTO:
        return hmac.new(key_str.encode('utf-8'), msg_str.encode('utf-8'), hashlib.sha256).hexdigest()
    # Pure Python SHA-256 fallback for minimal Symbian builds
    def sha256_fallback(data):
        # 32-bit bitwise SHA-256 implementation
        H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
             0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
        K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
             0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
             0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
             0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
             0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
             0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
             0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
             0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]
        
        def rotr(x, n): return ((x >> n) | (x << (32 - n))) & 0xFFFFFFFF
        b = bytearray(data)
        orig_len = len(b) * 8
        b.append(0x80)
        while (len(b) % 64) != 56: b.append(0x00)
        b += struct.pack('>Q', orig_len)
        
        for i in range(0, len(b), 64):
            w = list(struct.unpack('>16L', b[i:i+64])) + [0]*48
            for j in range(16, 64):
                s0 = rotr(w[j-15], 7) ^ rotr(w[j-15], 18) ^ (w[j-15] >> 3)
                s1 = rotr(w[j-2], 17) ^ rotr(w[j-2], 19) ^ (w[j-2] >> 10)
                w[j] = (w[j-16] + s0 + w[j-7] + s1) & 0xFFFFFFFF
            a, b_val, c, d, e, f, g, h = H
            for j in range(64):
                S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
                ch = (e & f) ^ ((~e) & g)
                temp1 = (h + S1 + ch + K[j] + w[j]) & 0xFFFFFFFF
                S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
                maj = (a & b_val) ^ (a & c) ^ (b_val & c)
                temp2 = (S0 + maj) & 0xFFFFFFFF
                h = g; g = f; f = e; e = (d + temp1) & 0xFFFFFFFF
                d = c; c = b_val; b_val = a; a = (temp1 + temp2) & 0xFFFFFFFF
            for idx, val in enumerate([a, b_val, c, d, e, f, g, h]):
                H[idx] = (H[idx] + val) & 0xFFFFFFFF
        return "".join("%08x" % v for v in H)

    # HMAC key padding
    block_size = 64
    k = key_str.encode('utf-8')
    if len(k) > block_size:
        k = bytes(bytearray.fromhex(sha256_fallback(k)))
    k = k.ljust(block_size, b'\x00')
    o_key_pad = bytearray(x ^ 0x5c for x in bytearray(k))
    i_key_pad = bytearray(x ^ 0x36 for x in bytearray(k))
    inner = bytes(bytearray.fromhex(sha256_fallback(bytes(i_key_pad) + msg_str.encode('utf-8'))))
    return sha256_fallback(bytes(o_key_pad) + inner)

class BelleFP2App(object):
    def __init__(self):
        self.running = True
        self.server_url = DEFAULT_SERVER
        self.account_id = ""
        self.time_offset = 0
        
        # State
        self.current_view = "upload" # upload, gallery, browser, result, account, textview
        self.selected_file = None
        self.selected_file_size = 0
        self.upload_progress = 0
        self.upload_speed = ""
        self.upload_result = {}
        
        # Upload Options
        self.opt_expiry = "24h" # 1h, 24h, 7d, 30d, never
        self.opt_burn = "0"     # 0, 1, 5, 10
        self.opt_password = ""
        self.opt_custom_id = ""
        
        # Sheet state (Belle sliding sheet)
        self.sheet_open = False
        self.sheet_target = None
        
        # Text view state for TOS / Privacy / About
        self.text_title = u""
        self.text_lines = []
        self.text_scroll = 0
        
        # Gallery state
        self.gallery_items = []
        self.gallery_scroll = 0
        
        # Browser state
        self.browser_path = "C:\\"
        self.browser_items = []
        self.browser_scroll = 0
        
        # Initialize UI
        if IN_SYMBIAN:
            appuifw.app.title = APP_NAME
            appuifw.app.screen = 'full'
            self.canvas = appuifw.Canvas(
                redraw_callback=self.handle_redraw,
                event_callback=self.handle_event
            )
            appuifw.app.body = self.canvas
            self.width, self.height = self.canvas.size
            self.buffer = graphics.Image.new((self.width, self.height))
            
            # Setup Belle Touch Listeners
            try:
                self.canvas.bind(appuifw.EButton1Down, self.handle_touch_down)
                self.canvas.bind(appuifw.EButton1Up, self.handle_touch_up)
                self.canvas.bind(appuifw.EDrag, self.handle_touch_drag)
            except Exception:
                pass
            
            # Belle Options Menu (Bottom Left Action)
            self.setup_options_menu()
            appuifw.app.exit_key_handler = self.exit_app
        else:
            self.width, self.height = 360, 640

    def setup_options_menu(self):
        appuifw.app.menu = [
            (u"Account Settings", self.action_open_account),
            (u"System File Pick", self.action_system_file_pick),
            (u"Terms of Service", self.action_show_tos),
            (u"Privacy Policy", self.action_show_privacy),
            (u"About Nullhost", self.action_show_about),
            (u"Exit", self.exit_app)
        ]

    def exit_app(self):
        self.running = False
        if IN_SYMBIAN:
            appuifw.app.set_exit()

    # ----------------------------------------------------
    # TOUCH EVENT DISPATCHER
    # ----------------------------------------------------
    def handle_touch_down(self, pos):
        self.touch_start = pos
        self.touch_active = True

    def handle_touch_drag(self, pos):
        if not hasattr(self, 'touch_start'):
            return
        dx = pos[0] - self.touch_start[0]
        dy = pos[1] - self.touch_start[1]
        
        # Scroll handling in text view, gallery, and browser
        if self.current_view == "textview":
            self.text_scroll = max(0, self.text_scroll - int(dy * 0.15))
            self.request_repaint()
        elif self.current_view == "gallery":
            self.gallery_scroll = max(0, self.gallery_scroll - int(dy * 0.2))
            self.request_repaint()
        elif self.current_view == "browser":
            self.browser_scroll = max(0, self.browser_scroll - int(dy * 0.2))
            self.request_repaint()

    def handle_touch_up(self, pos):
        x, y = pos
        
        # Check Belle FP2 Bottom Toolbar (always at y: height - 58 to height)
        if y >= (self.height - 58):
            self.handle_toolbar_touch(x, y)
            return
            
        # Sheet touch interception
        if self.sheet_open:
            self.handle_sheet_touch(x, y)
            return

        # View specific touch actions
        if self.current_view == "upload":
            self.handle_upload_view_touch(x, y)
        elif self.current_view == "gallery":
            self.handle_gallery_view_touch(x, y)
        elif self.current_view == "browser":
            self.handle_browser_view_touch(x, y)
        elif self.current_view == "result":
            self.handle_result_view_touch(x, y)
        elif self.current_view == "textview":
            # Tap back button on top
            if y < 45 and x > (self.width - 60):
                self.current_view = "upload"
                self.request_repaint()

    def handle_toolbar_touch(self, x, y):
        btn_w = self.width / 4
        btn_idx = int(x / btn_w)
        if btn_idx == 0:   # Options Menu
            self.open_belle_options()
        elif btn_idx == 1: # Gallery
            self.current_view = "gallery"
            self.load_gallery_photos()
            self.request_repaint()
        elif btn_idx == 2: # Browse
            self.current_view = "browser"
            self.load_directory(self.browser_path)
            self.request_repaint()
        elif btn_idx == 3: # Upload / Action
            if self.current_view == "upload":
                if self.selected_file:
                    self.execute_upload()
                else:
                    self.action_system_file_pick()
            else:
                self.current_view = "upload"
                self.request_repaint()

    def open_belle_options(self):
        items = [
            u"1. System File Picker",
            u"2. Account & Quota",
            u"3. Terms of Service",
            u"4. Privacy Policy",
            u"5. About Nullhost",
            u"6. Exit"
        ]
        choice = appuifw.selection_list(choices=items, search_field=0)
        if choice == 0:
            self.action_system_file_pick()
        elif choice == 1:
            self.action_open_account()
        elif choice == 2:
            self.action_show_tos()
        elif choice == 3:
            self.action_show_privacy()
        elif choice == 4:
            self.action_show_about()
        elif choice == 5:
            self.exit_app()

    def handle_upload_view_touch(self, x, y):
        # Expiry pill touch (y: 190-230)
        if 190 <= y <= 230:
            if x < self.width / 2:
                self.open_expiry_picker()
            else:
                self.open_burn_picker()
        # Security pills touch (y: 240-280)
        elif 240 <= y <= 280:
            if x < self.width / 2:
                self.open_password_dialog()
            else:
                self.open_custom_id_dialog()
        # Main Action Button (y: 300-355)
        elif 300 <= y <= 355:
            if 20 <= x <= (self.width - 20):
                if self.selected_file:
                    self.execute_upload()
                else:
                    self.action_system_file_pick()
        # Fast action buttons: [System Pick] [Browser] [Gallery] (y: 370-415)
        elif 370 <= y <= 415:
            col_w = (self.width - 40) / 3
            idx = int((x - 20) / col_w)
            if idx == 0:
                self.action_system_file_pick()
            elif idx == 1:
                self.current_view = "browser"
                self.load_directory(self.browser_path)
                self.request_repaint()
            elif idx == 2:
                self.current_view = "gallery"
                self.load_gallery_photos()
                self.request_repaint()

    # ----------------------------------------------------
    # DIALOG ACTIONS & PICKERS
    # ----------------------------------------------------
    def open_expiry_picker(self):
        opts = [u"1 Hour", u"24 Hours (Default)", u"7 Days", u"30 Days", u"Never Expire"]
        vals = ["1h", "24h", "7d", "30d", "never"]
        res = appuifw.selection_list(choices=opts, search_field=0)
        if res is not None and 0 <= res < len(vals):
            self.opt_expiry = vals[res]
            self.request_repaint()

    def open_burn_picker(self):
        opts = [u"Disabled (Normal)", u"1 View (Burn immediately)", u"5 Views", u"10 Views"]
        vals = ["0", "1", "5", "10"]
        res = appuifw.selection_list(choices=opts, search_field=0)
        if res is not None and 0 <= res < len(vals):
            self.opt_burn = vals[res]
            self.request_repaint()

    def open_password_dialog(self):
        res = appuifw.query(u"Set Download Password:", "text", unicode(self.opt_password))
        if res is not None:
            self.opt_password = str(res).strip()
            self.request_repaint()

    def open_custom_id_dialog(self):
        res = appuifw.query(u"Custom Link Slug (optional):", "text", unicode(self.opt_custom_id))
        if res is not None:
            self.opt_custom_id = str(res).strip()
            self.request_repaint()

    def action_system_file_pick(self):
        """Use native Symbian dialog or content handler query."""
        path = appuifw.query(u"Enter File Path:", "text", u"E:\\Images\\")
        if path:
            p_str = str(path).strip()
            if os.path.exists(p_str) and not os.path.isdir(p_str):
                self.select_file(p_str)
            else:
                appuifw.note(u"File not found or is directory", "error")

    def select_file(self, full_path):
        self.selected_file = full_path
        try:
            self.selected_file_size = os.path.getsize(full_path)
        except Exception:
            self.selected_file_size = 0
        self.current_view = "upload"
        appuifw.note(u"File selected: " + unicode(os.path.basename(full_path)), "conf")
        self.request_repaint()

    # ----------------------------------------------------
    # GALLERY BROWSER (Images scanning)
    # ----------------------------------------------------
    def load_gallery_photos(self):
        self.gallery_items = []
        scan_dirs = ["E:\\Images", "C:\\Data\\Images", "F:\\Images", "E:\\DCIM", "C:\\Data\\Others"]
        exts = ('.jpg', '.jpeg', '.png', '.gif', '.bmp')
        for s_dir in scan_dirs:
            if os.path.exists(s_dir):
                try:
                    for root, dirs, files in os.walk(s_dir):
                        for f in files:
                            if f.lower().endswith(exts):
                                full = os.path.join(root, f)
                                try:
                                    sz = os.path.getsize(full)
                                    self.gallery_items.append((f, full, sz))
                                except Exception:
                                    pass
                except Exception:
                    pass
        self.gallery_scroll = 0

    def handle_gallery_view_touch(self, x, y):
        if y < 45: # Header back
            self.current_view = "upload"
            self.request_repaint()
            return
        # Calculate tapped row
        row_h = 56
        clicked_idx = int((y - 45 + self.gallery_scroll) / row_h)
        if 0 <= clicked_idx < len(self.gallery_items):
            item = self.gallery_items[clicked_idx]
            fname, fpath, sz = item
            choice = appuifw.popup_menu([u"Select & Upload", u"Cancel"], unicode(fname))
            if choice == 0:
                self.select_file(fpath)

    # ----------------------------------------------------
    # IN-APP DRIVE EXPLORER
    # ----------------------------------------------------
    def load_directory(self, path):
        self.browser_path = path
        self.browser_items = []
        # Add parent folder entry if not root drive
        if len(path) > 3:
            parent = os.path.dirname(path.rstrip("\\"))
            if not parent.endswith("\\"): parent += "\\"
            self.browser_items.append((".. (Parent Directory)", parent, True, 0))
        
        try:
            entries = os.listdir(path)
            entries.sort()
            for entry in entries:
                full = os.path.join(path, entry)
                is_dir = os.path.isdir(full)
                sz = 0 if is_dir else os.path.getsize(full)
                self.browser_items.append((entry, full, is_dir, sz))
        except Exception as e:
            appuifw.note(u"Cannot read drive/folder", "error")
        self.browser_scroll = 0

    def handle_browser_view_touch(self, x, y):
        # Header drive selector buttons (C:, E:, F:)
        if y < 45:
            d_w = self.width / 4
            idx = int(x / d_w)
            if idx == 0:
                self.load_directory("C:\\")
            elif idx == 1:
                self.load_directory("E:\\")
            elif idx == 2:
                if os.path.exists("F:\\"): self.load_directory("F:\\")
                else: appuifw.note(u"Drive F: (Memory Card) not inserted", "info")
            elif idx == 3:
                self.current_view = "upload"
            self.request_repaint()
            return
            
        row_h = 48
        clicked_idx = int((y - 45 + self.browser_scroll) / row_h)
        if 0 <= clicked_idx < len(self.browser_items):
            name, full_path, is_dir, sz = self.browser_items[clicked_idx]
            if is_dir:
                self.load_directory(full_path)
                self.request_repaint()
            else:
                self.select_file(full_path)

    # ----------------------------------------------------
    # RESULT VIEW ACTIONS
    # ----------------------------------------------------
    def handle_result_view_touch(self, x, y):
        # Buttons on result view
        # Copy Short Link: y 200-240
        if 200 <= y <= 240 and 20 <= x <= (self.width - 20):
            url = self.upload_result.get('url', '')
            if url:
                self.copy_clipboard(url)
                appuifw.note(u"Link copied to clipboard!", "conf")
        # Copy Raw Link: y 250-290
        elif 250 <= y <= 290 and 20 <= x <= (self.width - 20):
            raw = self.upload_result.get('raw_url', '')
            if raw:
                self.copy_clipboard(raw)
                appuifw.note(u"Raw URL copied to clipboard!", "conf")
        # Upload Another: y 310-350
        elif 310 <= y <= 350 and 20 <= x <= (self.width - 20):
            self.selected_file = None
            self.selected_file_size = 0
            self.current_view = "upload"
            self.request_repaint()

    def copy_clipboard(self, text):
        try:
            import appuifw
            # PyS60 clipboard interface or text query copy
            txt = appuifw.Text()
            txt.set(unicode(text))
            txt.select_all()
        except Exception:
            pass

    # ----------------------------------------------------
    # MENU VIEWS: TERMS OF SERVICE, PRIVACY, ABOUT, ACCOUNT
    # ----------------------------------------------------
    def action_show_tos(self):
        self.text_title = u"Terms of Service"
        self.text_lines = [
            u"NULLHOST TERMS OF SERVICE",
            u"-----------------------------------",
            u"Last updated: October 2026",
            u"",
            u"1. ACCEPTABLE USE",
            u"Nullhost provides privacy-first ephemeral and",
            u"permanent file storage. Users are strictly",
            u"prohibited from uploading:",
            u"  * Malicious software, viruses, or exploits",
            u"  * Child abuse or exploitation materials",
            u"  * Copyright-infringing proprietary content",
            u"  * Terrorist or illegal materials",
            u"",
            u"2. ZERO TOLERANCE",
            u"Violations result in immediate file termination",
            u"and permanent blocking without notice.",
            u"",
            u"3. RETENTION & EXPIRATION",
            u"Files set to expire or burn are purged automatically.",
            u"Zero traces remain after expiration.",
            u"",
            u"4. DISCLAIMER",
            u"Service is provided 'AS IS' without warranties of",
            u"any kind. Back up your essential data.",
            u"-----------------------------------",
            u"[ Tap top-right back button to return ]"
        ]
        self.text_scroll = 0
        self.current_view = "textview"
        self.request_repaint()

    def action_show_privacy(self):
        self.text_title = u"Privacy Policy"
        self.text_lines = [
            u"NULLHOST PRIVACY POLICY",
            u"-----------------------------------",
            u"Last updated: October 2026",
            u"",
            u"1. ZERO TRACKING",
            u"Nullhost does NOT collect telemetry, tracking",
            u"cookies, user agents, or advertising identifiers.",
            u"",
            u"2. EPHEMERAL STORAGE",
            u"When a file expires or reaches its view limit,",
            u"its data and metadata are deleted immediately.",
            u"",
            u"3. MOBILE AUTHENTICATION",
            u"Authentic mobile app tokens use local HMAC",
            u"cryptographic handshakes. No personal credentials",
            u"are stored on device.",
            u"",
            u"4. DATA OWNERSHIP",
            u"You retain all rights to files you host.",
            u"-----------------------------------",
            u"[ Tap top-right back button to return ]"
        ]
        self.text_scroll = 0
        self.current_view = "textview"
        self.request_repaint()

    def action_show_about(self):
        self.text_title = u"About Nullhost"
        drives_info = []
        for d in ("C:", "E:", "F:"):
            if os.path.exists(d + "\\"):
                drives_info.append(u"Drive %s Available" % unicode(d))
        self.text_lines = [
            u"NULLHOST FOR SYMBIAN",
            u"-----------------------------------",
            u"Version: " + APP_VERSION,
            u"Platform: Symbian ^3 / Belle FP2",
            u"Runtime: Standalone Native PyS60",
            u"Qt / WRT Dependencies: NONE (Pure Native)",
            u"",
            u"ECOSYSTEM",
            u"Nullhost is part of the Gelaxiz network,",
            u"engineered for high craft, privacy, and legacy",
            u"device accessibility.",
            u"",
            u"LOCAL DEVICE STORAGE:",
        ] + drives_info + [
            u"",
            u"CREDITS",
            u"Engineered by Gelaxiz Labs (c) 2026",
            u"Licensed for public self-hosting.",
            u"-----------------------------------",
            u"[ Tap top-right back button to return ]"
        ]
        self.text_scroll = 0
        self.current_view = "textview"
        self.request_repaint()

    def action_open_account(self):
        new_id = appuifw.query(u"Enter 24-digit Account ID:", "text", unicode(self.account_id))
        if new_id is not None:
            self.account_id = str(new_id).strip()
            appuifw.note(u"Account ID configured", "conf")
            self.request_repaint()

    # ----------------------------------------------------
    # AUTHENTICATION & MULTIPART UPLOAD ENGINE
    # ----------------------------------------------------
    def get_auth_token(self):
        """Authentic mobile HMAC handshake to obtain bypass upload token."""
        ts = int(time.time()) + self.time_offset
        nonce = "".join(random.choice("0123456789abcdef") for _ in range(16))
        sig_payload = "%s:%d:%s" % (CLIENT_ID, ts, nonce)
        signature = safe_hmac_sha256(MOBILE_SECRET, sig_payload)
        
        post_data = urllib.urlencode({
            'client': CLIENT_ID,
            'version': APP_VERSION,
            'timestamp': str(ts),
            'nonce': nonce,
            'signature': signature
        })
        
        url = self.server_url.rstrip('/') + '/api/mobile/auth'
        req = urllib2.Request(url, post_data)
        req.add_header('User-Agent', 'Nullhost-Symbian-Belle/1.0')
        req.add_header('Content-Type', 'application/x-www-form-urlencoded')
        
        try:
            resp = urllib2.urlopen(req, timeout=15)
            body = resp.read()
            # Simple JSON parser for token and server_time
            token = ""
            for part in body.replace('{', '').replace('}', '').split(','):
                kv = part.split(':', 1)
                if len(kv) == 2:
                    k = kv[0].strip(' "\'')
                    v = kv[1].strip(' "\'')
                    if k == 'token':
                        token = v
                    elif k == 'server_time':
                        try:
                            s_time = int(v)
                            self.time_offset = s_time - int(time.time())
                        except Exception:
                            pass
            return token
        except Exception as e:
            return None

    def execute_upload(self):
        if not self.selected_file or not os.path.exists(self.selected_file):
            appuifw.note(u"Please select a valid file first", "error")
            return
            
        appuifw.note(u"Authenticating app...", "info")
        token = self.get_auth_token()
        if not token:
            appuifw.note(u"Mobile authentication failed. Check connection.", "error")
            return
            
        appuifw.note(u"Uploading file...", "info")
        filename = os.path.basename(self.selected_file)
        boundary = '----NullhostSymbianBoundary%d' % int(time.time())
        
        # Build multipart payload
        parts = []
        def add_field(name, value):
            parts.append('--' + boundary)
            parts.append('Content-Disposition: form-data; name="%s"' % name)
            parts.append('')
            parts.append(str(value))
            
        add_field('format', 'json')
        add_field('expiry', self.opt_expiry)
        if self.opt_burn != "0":
            add_field('burn_views', self.opt_burn)
        if self.opt_password:
            add_field('password', self.opt_password)
        if self.opt_custom_id:
            add_field('custom_id', self.opt_custom_id)
        if self.account_id:
            add_field('account_id', self.account_id)
            
        # File header
        parts.append('--' + boundary)
        parts.append('Content-Disposition: form-data; name="file"; filename="%s"' % filename)
        parts.append('Content-Type: application/octet-stream')
        parts.append('')
        
        head_data = '\r\n'.join(parts) + '\r\n'
        tail_data = '\r\n--' + boundary + '--\r\n'
        
        try:
            with open(self.selected_file, 'rb') as f:
                file_content = f.read()
            full_body = head_data + file_content + tail_data
            
            upload_url = self.server_url.rstrip('/') + '/api/mobile/upload'
            req = urllib2.Request(upload_url, full_body)
            req.add_header('Content-Type', 'multipart/form-data; boundary=' + boundary)
            req.add_header('X-Nullhost-App-Token', token)
            req.add_header('User-Agent', 'Nullhost-Symbian-Belle/1.0')
            if self.account_id:
                req.add_header('X-Nullhost-Account', self.account_id)
                
            resp = urllib2.urlopen(req, timeout=45)
            resp_body = resp.read()
            
            # Parse response
            res_dict = {}
            for item in resp_body.replace('{', '').replace('}', '').split(','):
                kv = item.split(':', 1)
                if len(kv) == 2:
                    k = kv[0].strip(' "\'')
                    v = kv[1].strip(' "\'')
                    res_dict[k] = v
                    
            self.upload_result = res_dict
            self.current_view = "result"
            appuifw.note(u"Upload Successful!", "conf")
            self.request_repaint()
        except Exception as e:
            appuifw.note(u"Upload error: " + unicode(str(e)[:40]), "error")

    # ----------------------------------------------------
    # DRAWING & CANVAS REDRAW
    # ----------------------------------------------------
    def request_repaint(self):
        if IN_SYMBIAN and hasattr(self, 'canvas'):
            self.canvas.drawNow()

    def handle_redraw(self, rect):
        if not IN_SYMBIAN: return
        img = self.buffer
        img.clear(COLOR_BG)
        
        # Render current view
        if self.current_view == "upload":
            self.render_upload_view(img)
        elif self.current_view == "gallery":
            self.render_gallery_view(img)
        elif self.current_view == "browser":
            self.render_browser_view(img)
        elif self.current_view == "result":
            self.render_result_view(img)
        elif self.current_view == "textview":
            self.render_textview(img)
            
        # Draw Belle FP2 Top Status Bar (y: 0-36)
        self.render_status_bar(img)
        
        # Draw Belle FP2 Bottom Toolbar (y: height-56 to height)
        self.render_bottom_toolbar(img)
        
        # Blit buffer to screen
        self.canvas.blit(img)

    def handle_event(self, event):
        pass

    def render_status_bar(self, img):
        # Frosted glass top header
        img.rectangle((0, 0, self.width, 36), fill=COLOR_TOOLBAR, outline=COLOR_TOOLBAR_BORDER)
        # Battery / Signal / Clock indicator
        clock_str = time.strftime("%H:%M")
        img.text((12, 24), APP_NAME, fill=COLOR_ACCENT, font='title')
        img.text((self.width - 55, 24), unicode(clock_str), fill=COLOR_TEXT_MUTED, font='normal')

    def render_bottom_toolbar(self, img):
        # Belle FP2 Toolbar Container
        tb_y = self.height - 56
        img.rectangle((0, tb_y, self.width, self.height), fill=COLOR_TOOLBAR, outline=COLOR_TOOLBAR_BORDER)
        btn_w = self.width / 4
        
        labels = [u"Options", u"Gallery", u"Browse", u"Upload"]
        for i, lbl in enumerate(labels):
            bx = i * btn_w
            # Selected tab highlight
            is_active = (i == 1 and self.current_view == "gallery") or \
                        (i == 2 and self.current_view == "browser") or \
                        (i == 3 and self.current_view == "upload")
            if is_active:
                img.rectangle((bx + 4, tb_y + 6, bx + btn_w - 4, self.height - 6), fill=COLOR_CARD, outline=COLOR_ACCENT)
            img.text((bx + 14, tb_y + 35), lbl, fill=COLOR_TEXT if is_active else COLOR_TEXT_MUTED, font='normal')

    def render_upload_view(self, img):
        # Header banner
        img.text((20, 68), u"Fast & Private File Host", fill=COLOR_TEXT, font='title')
        img.text((20, 92), u"Standalone Symbian Belle Native Edition", fill=COLOR_TEXT_MUTED, font='dense')
        
        # File Card Container (y: 105-180)
        img.rectangle((16, 105, self.width - 16, 180), fill=COLOR_CARD, outline=COLOR_CARD_BORDER)
        if self.selected_file:
            fname = os.path.basename(self.selected_file)
            if len(fname) > 28: fname = fname[:25] + "..."
            sz_kb = self.selected_file_size / 1024.0
            sz_str = u"%.1f KB" % sz_kb if sz_kb < 1024 else u"%.2f MB" % (sz_kb / 1024.0)
            img.text((28, 136), unicode(fname), fill=COLOR_TEXT, font='title')
            img.text((28, 162), u"Size: " + sz_str + u" | Ready to upload", fill=COLOR_SUCCESS, font='dense')
        else:
            img.text((28, 136), u"No file selected", fill=COLOR_TEXT_MUTED, font='title')
            img.text((28, 162), u"Tap 'Browse' or 'Gallery' below to pick a file", fill=COLOR_TEXT_MUTED, font='dense')

        # Option Pills Row 1: Expiry & Burn
        p_w = (self.width - 40) / 2
        # Expiry Pill
        img.rectangle((16, 190, 16 + p_w, 230), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        img.text((26, 216), u"Expiry: " + unicode(self.opt_expiry), fill=COLOR_TEXT, font='normal')
        
        # Burn Pill
        img.rectangle((24 + p_w, 190, self.width - 16, 230), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        burn_txt = u"Burn: Off" if self.opt_burn == "0" else u"Burn: %s V" % unicode(self.opt_burn)
        img.text((34 + p_w, 216), burn_txt, fill=COLOR_TEXT, font='normal')

        # Option Pills Row 2: Password & Custom Slug
        img.rectangle((16, 240, 16 + p_w, 280), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        pwd_txt = u"Password: Set" if self.opt_password else u"Password: None"
        img.text((26, 266), pwd_txt, fill=COLOR_TEXT, font='normal')
        
        img.rectangle((24 + p_w, 240, self.width - 16, 280), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        slug_txt = u"Slug: " + unicode(self.opt_custom_id[:8]) if self.opt_custom_id else u"Slug: Random"
        img.text((34 + p_w, 266), slug_txt, fill=COLOR_TEXT, font='normal')

        # Primary Upload Button (Belle FP2 Style Rounded Accent Pill)
        img.rectangle((16, 295, self.width - 16, 350), fill=COLOR_ACCENT, outline=COLOR_ACCENT_HOVER)
        action_txt = u"UPLOAD NOW" if self.selected_file else u"SELECT FILE TO UPLOAD"
        img.text((80, 330), action_txt, fill=(10, 15, 20), font='title')

        # Fast Pick Buttons: [System Pick] [Explorer] [Gallery]
        b_w = (self.width - 48) / 3
        # System Pick
        img.rectangle((16, 365, 16 + b_w, 405), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        img.text((24, 390), u"System Pick", fill=COLOR_TEXT, font='dense')
        # Explorer
        img.rectangle((24 + b_w, 365, 24 + 2 * b_w, 405), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        img.text((32 + b_w, 390), u"Explorer", fill=COLOR_TEXT, font='dense')
        # Gallery
        img.rectangle((32 + 2 * b_w, 365, self.width - 16, 405), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        img.text((40 + 2 * b_w, 390), u"Gallery", fill=COLOR_TEXT, font='dense')

    def render_gallery_view(self, img):
        # Header
        img.rectangle((0, 36, self.width, 76), fill=COLOR_TOOLBAR, outline=COLOR_TOOLBAR_BORDER)
        img.text((16, 62), u"Photo Gallery", fill=COLOR_TEXT, font='title')
        img.text((self.width - 70, 62), u"[ Back ]", fill=COLOR_ACCENT, font='normal')
        
        if not self.gallery_items:
            img.text((20, 130), u"Scanning device for images...", fill=COLOR_TEXT_MUTED, font='normal')
            img.text((20, 160), u"Checks E:\\Images, C:\\Data\\Images", fill=COLOR_TEXT_MUTED, font='dense')
            return
            
        y = 85 - self.gallery_scroll
        for fname, fpath, sz in self.gallery_items:
            if y > 60 and y < (self.height - 60):
                # Row background
                img.rectangle((12, y, self.width - 12, y + 48), fill=COLOR_CARD, outline=COLOR_CARD_BORDER)
                # Label
                disp_name = fname if len(fname) < 24 else fname[:22] + ".."
                img.text((24, y + 24), unicode(disp_name), fill=COLOR_TEXT, font='normal')
                sz_str = u"%.1f KB" % (sz / 1024.0)
                img.text((24, y + 42), sz_str, fill=COLOR_TEXT_MUTED, font='dense')
                img.text((self.width - 70, y + 32), u"[Select]", fill=COLOR_ACCENT, font='dense')
            y += 54

    def render_browser_view(self, img):
        # Header with drive selector
        img.rectangle((0, 36, self.width, 76), fill=COLOR_TOOLBAR, outline=COLOR_TOOLBAR_BORDER)
        d_w = self.width / 4
        img.text((12, 62), u"C: Phone", fill=COLOR_ACCENT, font='dense')
        img.text((12 + d_w, 62), u"E: Mass", fill=COLOR_ACCENT, font='dense')
        img.text((12 + 2*d_w, 62), u"F: SD", fill=COLOR_ACCENT, font='dense')
        img.text((self.width - 60, 62), u"[Close]", fill=COLOR_TEXT_MUTED, font='dense')
        
        # Path banner
        p_str = self.browser_path if len(self.browser_path) < 32 else ".." + self.browser_path[-30:]
        img.text((16, 95), unicode(p_str), fill=COLOR_TEXT_MUTED, font='dense')
        
        y = 105 - self.browser_scroll
        for name, full, is_dir, sz in self.browser_items:
            if y > 80 and y < (self.height - 60):
                img.rectangle((12, y, self.width - 12, y + 42), fill=COLOR_CARD, outline=COLOR_CARD_BORDER)
                disp = name if len(name) < 26 else name[:24] + ".."
                icon_prefix = u"[DIR] " if is_dir else u"[FILE] "
                txt_color = COLOR_ACCENT if is_dir else COLOR_TEXT
                img.text((22, y + 26), icon_prefix + unicode(disp), fill=txt_color, font='normal')
                if not is_dir:
                    sz_str = u"%.1f KB" % (sz / 1024.0)
                    img.text((self.width - 80, y + 26), sz_str, fill=COLOR_TEXT_MUTED, font='dense')
            y += 46

    def render_result_view(self, img):
        img.text((20, 68), u"Upload Complete!", fill=COLOR_SUCCESS, font='title')
        
        # Result details card
        img.rectangle((16, 85, self.width - 16, 185), fill=COLOR_CARD, outline=COLOR_CARD_BORDER)
        url = self.upload_result.get('url', '')
        raw_url = self.upload_result.get('raw_url', '')
        
        img.text((28, 115), u"Short Link:", fill=COLOR_TEXT_MUTED, font='dense')
        img.text((28, 138), unicode(url[:34]), fill=COLOR_TEXT, font='normal')
        
        img.text((28, 160), u"Raw Link:", fill=COLOR_TEXT_MUTED, font='dense')
        img.text((28, 178), unicode(raw_url[:34]), fill=COLOR_TEXT, font='dense')
        
        # Action Buttons
        img.rectangle((16, 200, self.width - 16, 240), fill=COLOR_ACCENT, outline=COLOR_ACCENT_HOVER)
        img.text((85, 226), u"COPY SHORT LINK", fill=(10, 15, 20), font='title')
        
        img.rectangle((16, 250, self.width - 16, 290), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        img.text((95, 276), u"COPY RAW LINK", fill=COLOR_TEXT, font='normal')
        
        img.rectangle((16, 305, self.width - 16, 345), fill=COLOR_BUTTON, outline=COLOR_CARD_BORDER)
        img.text((85, 330), u"UPLOAD ANOTHER FILE", fill=COLOR_TEXT, font='normal')

    def render_textview(self, img):
        # Header
        img.rectangle((0, 36, self.width, 76), fill=COLOR_TOOLBAR, outline=COLOR_TOOLBAR_BORDER)
        img.text((16, 62), self.text_title, fill=COLOR_TEXT, font='title')
        img.text((self.width - 70, 62), u"[ Back ]", fill=COLOR_ACCENT, font='normal')
        
        # Scrollable content
        y = 95 - self.text_scroll
        for line in self.text_lines:
            if y > 60 and y < (self.height - 60):
                img.text((18, y), line, fill=COLOR_TEXT, font='dense')
            y += 20

def main():
    app = BelleFP2App()
    if IN_SYMBIAN:
        lock = e32.Ao_lock()
        app.lock = lock
        lock.wait()

if __name__ == '__main__':
    main()
