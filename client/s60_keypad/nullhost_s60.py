# -*- coding: utf-8 -*-
# Nullhost for Series 60 (S60 3rd / 5th Edition Keypad Devices)
# Pure Standalone Native Application - NO Qt, NO Web Runtime (WRT)
# Hardware D-Pad & CBA Softkey Ergonomics
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
    import key_codes
    IN_SYMBIAN = True
except ImportError:
    IN_SYMBIAN = False

# Crypto
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
except ImportError:
    pass

APP_NAME = u"Nullhost S60"
APP_VERSION = u"1.0.0"
CLIENT_ID = "symbian-s60-keypad"
DEFAULT_SERVER = "https://nullhost.gelaxiz.fi"
MOBILE_SECRET = "nullhost_secret_mobile_app_key_2026"

# Color Palette - Classic S60 High-Contrast Keypad Theme
COLOR_BG = (20, 24, 30)
COLOR_HEADER = (12, 16, 22)
COLOR_TAB_BG = (28, 34, 44)
COLOR_TAB_ACTIVE = (0, 114, 206)
COLOR_ROW_EVEN = (24, 28, 36)
COLOR_ROW_ODD = (30, 36, 46)
COLOR_CURSOR = (0, 102, 204)
COLOR_TEXT = (255, 255, 255)
COLOR_TEXT_MUTED = (160, 172, 188)
COLOR_ACCENT = (0, 180, 255)
COLOR_SUCCESS = (50, 205, 50)
COLOR_BORDER = (44, 52, 68)

def safe_hmac_sha256(key_str, msg_str):
    if HAS_NATIVE_CRYPTO:
        return hmac.new(key_str.encode('utf-8'), msg_str.encode('utf-8'), hashlib.sha256).hexdigest()
    # 32-bit SHA-256 fallback
    def sha256_fallback(data):
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

    block_size = 64
    k = key_str.encode('utf-8')
    if len(k) > block_size:
        k = bytes(bytearray.fromhex(sha256_fallback(k)))
    k = k.ljust(block_size, b'\x00')
    o_key_pad = bytearray(x ^ 0x5c for x in bytearray(k))
    i_key_pad = bytearray(x ^ 0x36 for x in bytearray(k))
    inner = bytes(bytearray.fromhex(sha256_fallback(bytes(i_key_pad) + msg_str.encode('utf-8'))))
    return sha256_fallback(bytes(o_key_pad) + inner)

class S60KeypadApp(object):
    def __init__(self):
        self.server_url = DEFAULT_SERVER
        self.account_id = ""
        self.time_offset = 0
        
        # Tabs: 0: Upload, 1: Browse, 2: Gallery, 3: Account, 4: Viewer (TOS/Privacy/About)
        self.tab_index = 0
        self.tabs = [u"Upload", u"Browse", u"Gallery", u"Account"]
        
        # Upload Options
        self.selected_file = None
        self.selected_file_size = 0
        self.opt_expiry = "24h"
        self.opt_burn = "0"
        self.opt_password = ""
        self.opt_custom_id = ""
        self.cursor_upload = 0 # Cursor index in upload form items
        
        # Browser State
        self.browser_path = "C:\\"
        self.browser_items = []
        self.cursor_browser = 0
        
        # Gallery State
        self.gallery_items = []
        self.cursor_gallery = 0
        
        # Result State
        self.upload_result = {}
        
        # Text Viewer (TOS / Privacy / About)
        self.viewer_title = u""
        self.viewer_lines = []
        self.viewer_scroll = 0
        
        if IN_SYMBIAN:
            appuifw.app.title = APP_NAME
            appuifw.app.screen = 'normal'
            self.canvas = appuifw.Canvas(
                redraw_callback=self.handle_redraw,
                event_callback=self.handle_event
            )
            appuifw.app.body = self.canvas
            self.width, self.height = self.canvas.size
            self.buffer = graphics.Image.new((self.width, self.height))
            
            # Setup Hardware Keypad D-Pad Binds
            self.bind_keys()
            
            # Setup CBA Softkeys: Left = Options Menu, Right = Back / Exit
            self.setup_cba_menu()
            appuifw.app.exit_key_handler = self.handle_right_softkey
        else:
            self.width, self.height = 240, 320

    def bind_keys(self):
        try:
            self.canvas.bind(key_codes.EKeyUpArrow, self.key_up)
            self.canvas.bind(key_codes.EKeyDownArrow, self.key_down)
            self.canvas.bind(key_codes.EKeyLeftArrow, self.key_left)
            self.canvas.bind(key_codes.EKeyRightArrow, self.key_right)
            self.canvas.bind(key_codes.EKeySelect, self.key_select)
            
            # Number shortcuts
            self.canvas.bind(key_codes.EKey1, lambda: self.number_shortcut(1))
            self.canvas.bind(key_codes.EKey2, lambda: self.number_shortcut(2))
            self.canvas.bind(key_codes.EKey3, lambda: self.number_shortcut(3))
            self.canvas.bind(key_codes.EKey4, lambda: self.number_shortcut(4))
        except Exception:
            pass

    def setup_cba_menu(self):
        appuifw.app.menu = [
            (u"Upload Current File", self.action_upload_now),
            (u"System File Pick", self.action_system_file_pick),
            (u"Change Expiry", self.action_change_expiry),
            (u"Change Burn Limit", self.action_change_burn),
            (u"Set Password", self.action_set_password),
            (u"Custom Link Slug", self.action_set_slug),
            (u"Terms of Service", self.action_show_tos),
            (u"Privacy Policy", self.action_show_privacy),
            (u"About Nullhost", self.action_show_about),
            (u"Exit", self.exit_app)
        ]

    def handle_right_softkey(self):
        if self.tab_index == 4: # In Viewer
            self.tab_index = 0
            self.request_repaint()
        elif self.tab_index != 0:
            self.tab_index = 0
            self.request_repaint()
        else:
            self.exit_app()

    def exit_app(self):
        if IN_SYMBIAN:
            appuifw.app.set_exit()

    # ----------------------------------------------------
    # HARDWARE KEYPAD NAVIGATION
    # ----------------------------------------------------
    def key_left(self):
        if self.tab_index == 4: return
        self.tab_index = (self.tab_index - 1) % len(self.tabs)
        self.on_tab_switched()
        self.request_repaint()

    def key_right(self):
        if self.tab_index == 4: return
        self.tab_index = (self.tab_index + 1) % len(self.tabs)
        self.on_tab_switched()
        self.request_repaint()

    def key_up(self):
        if self.tab_index == 0: # Upload form
            self.cursor_upload = max(0, self.cursor_upload - 1)
        elif self.tab_index == 1: # Browser
            self.cursor_browser = max(0, self.cursor_browser - 1)
        elif self.tab_index == 2: # Gallery
            self.cursor_gallery = max(0, self.cursor_gallery - 1)
        elif self.tab_index == 4: # Viewer
            self.viewer_scroll = max(0, self.viewer_scroll - 1)
        self.request_repaint()

    def key_down(self):
        if self.tab_index == 0: # Upload form
            self.cursor_upload = min(5, self.cursor_upload + 1)
        elif self.tab_index == 1: # Browser
            self.cursor_browser = min(max(0, len(self.browser_items) - 1), self.cursor_browser + 1)
        elif self.tab_index == 2: # Gallery
            self.cursor_gallery = min(max(0, len(self.gallery_items) - 1), self.cursor_gallery + 1)
        elif self.tab_index == 4: # Viewer
            self.viewer_scroll = min(max(0, len(self.viewer_lines) - 8), self.viewer_scroll + 1)
        self.request_repaint()

    def key_select(self):
        if self.tab_index == 0:
            self.handle_upload_select()
        elif self.tab_index == 1:
            self.handle_browser_select()
        elif self.tab_index == 2:
            self.handle_gallery_select()
        elif self.tab_index == 3:
            self.handle_account_select()
        elif self.tab_index == 4:
            self.tab_index = 0
            self.request_repaint()

    def number_shortcut(self, num):
        if 1 <= num <= 4:
            self.tab_index = num - 1
            self.on_tab_switched()
            self.request_repaint()

    def on_tab_switched(self):
        if self.tab_index == 1 and not self.browser_items:
            self.load_directory(self.browser_path)
        elif self.tab_index == 2 and not self.gallery_items:
            self.load_gallery_photos()

    # ----------------------------------------------------
    # TAB 0: UPLOAD FORM ACTIONS
    # ----------------------------------------------------
    def handle_upload_select(self):
        c = self.cursor_upload
        if c == 0: # File selector
            choice = appuifw.popup_menu([u"System File Pick", u"In-App Drive Explorer", u"Photo Gallery"], u"Choose Source:")
            if choice == 0: self.action_system_file_pick()
            elif choice == 1:
                self.tab_index = 1
                self.load_directory(self.browser_path)
                self.request_repaint()
            elif choice == 2:
                self.tab_index = 2
                self.load_gallery_photos()
                self.request_repaint()
        elif c == 1: self.action_change_expiry()
        elif c == 2: self.action_change_burn()
        elif c == 3: self.action_set_password()
        elif c == 4: self.action_set_slug()
        elif c == 5: self.action_upload_now()

    def action_system_file_pick(self):
        path = appuifw.query(u"File path to upload:", "text", u"E:\\Images\\")
        if path:
            p_str = str(path).strip()
            if os.path.exists(p_str) and not os.path.isdir(p_str):
                self.selected_file = p_str
                self.selected_file_size = os.path.getsize(p_str)
                self.tab_index = 0
                appuifw.note(u"File Selected!", "conf")
                self.request_repaint()
            else:
                appuifw.note(u"File not found", "error")

    def action_change_expiry(self):
        opts = [u"1 Hour", u"24 Hours (Default)", u"7 Days", u"30 Days", u"Never Expire"]
        vals = ["1h", "24h", "7d", "30d", "never"]
        ch = appuifw.selection_list(choices=opts, search_field=0)
        if ch is not None and 0 <= ch < len(vals):
            self.opt_expiry = vals[ch]
            self.request_repaint()

    def action_change_burn(self):
        opts = [u"Disabled", u"1 View (Burn)", u"5 Views", u"10 Views"]
        vals = ["0", "1", "5", "10"]
        ch = appuifw.selection_list(choices=opts, search_field=0)
        if ch is not None and 0 <= ch < len(vals):
            self.opt_burn = vals[ch]
            self.request_repaint()

    def action_set_password(self):
        pwd = appuifw.query(u"Download Password:", "text", unicode(self.opt_password))
        if pwd is not None:
            self.opt_password = str(pwd).strip()
            self.request_repaint()

    def action_set_slug(self):
        slug = appuifw.query(u"Custom Link Slug:", "text", unicode(self.opt_custom_id))
        if slug is not None:
            self.opt_custom_id = str(slug).strip()
            self.request_repaint()

    # ----------------------------------------------------
    # TAB 1: BROWSER ACTIONS
    # ----------------------------------------------------
    def load_directory(self, path):
        self.browser_path = path
        self.browser_items = []
        if len(path) > 3:
            parent = os.path.dirname(path.rstrip("\\"))
            if not parent.endswith("\\"): parent += "\\"
            self.browser_items.append((".. (Parent)", parent, True, 0))
            
        # Add root drives shortcut at top of root
        if path == "C:\\":
            if os.path.exists("E:\\"): self.browser_items.append(("[Drive E: Mass Storage]", "E:\\", True, 0))
            if os.path.exists("F:\\"): self.browser_items.append(("[Drive F: Memory Card]", "F:\\", True, 0))
        elif path == "E:\\":
            self.browser_items.append(("[Drive C: Phone Memory]", "C:\\", True, 0))
            if os.path.exists("F:\\"): self.browser_items.append(("[Drive F: Memory Card]", "F:\\", True, 0))

        try:
            entries = os.listdir(path)
            entries.sort()
            for e in entries:
                full = os.path.join(path, e)
                is_dir = os.path.isdir(full)
                sz = 0 if is_dir else os.path.getsize(full)
                self.browser_items.append((e, full, is_dir, sz))
        except Exception:
            appuifw.note(u"Cannot read drive/folder", "error")
        self.cursor_browser = 0

    def handle_browser_select(self):
        if not self.browser_items: return
        item = self.browser_items[self.cursor_browser]
        name, full, is_dir, sz = item
        if is_dir:
            self.load_directory(full)
            self.request_repaint()
        else:
            self.selected_file = full
            self.selected_file_size = sz
            self.tab_index = 0
            appuifw.note(u"Selected: " + unicode(name[:20]), "conf")
            self.request_repaint()

    # ----------------------------------------------------
    # TAB 2: GALLERY ACTIONS
    # ----------------------------------------------------
    def load_gallery_photos(self):
        self.gallery_items = []
        scan_dirs = ["E:\\Images", "C:\\Data\\Images", "F:\\Images"]
        exts = ('.jpg', '.jpeg', '.png', '.gif', '.bmp')
        for s in scan_dirs:
            if os.path.exists(s):
                try:
                    for root, dirs, files in os.walk(s):
                        for f in files:
                            if f.lower().endswith(exts):
                                full = os.path.join(root, f)
                                sz = os.path.getsize(full)
                                self.gallery_items.append((f, full, sz))
                except Exception:
                    pass
        self.cursor_gallery = 0

    def handle_gallery_select(self):
        if not self.gallery_items: return
        fname, fpath, sz = self.gallery_items[self.cursor_gallery]
        self.selected_file = fpath
        self.selected_file_size = sz
        self.tab_index = 0
        appuifw.note(u"Photo selected!", "conf")
        self.request_repaint()

    # ----------------------------------------------------
    # TAB 3: ACCOUNT & SETTINGS
    # ----------------------------------------------------
    def handle_account_select(self):
        aid = appuifw.query(u"24-digit Account ID:", "text", unicode(self.account_id))
        if aid is not None:
            self.account_id = str(aid).strip()
            appuifw.note(u"Saved Account ID", "conf")
            self.request_repaint()

    # ----------------------------------------------------
    # MENU VIEWS: TERMS, PRIVACY, ABOUT
    # ----------------------------------------------------
    def action_show_tos(self):
        self.viewer_title = u"Terms of Service"
        self.viewer_lines = [
            u"NULLHOST TERMS OF SERVICE",
            u"-----------------------------",
            u"1. Acceptable Use Policy:",
            u"Nullhost provides ephemeral",
            u"and permanent file hosting.",
            u"Strictly prohibited content:",
            u"  * Malware, viruses, exploits",
            u"  * Child sexual abuse material",
            u"  * Copyright infringement",
            u"  * Illegal or harmful material",
            u"",
            u"2. Zero Tolerance:",
            u"Prohibited content is purged",
            u"immediately without warning.",
            u"",
            u"3. Ephemeral Expiration:",
            u"Expired files are completely",
            u"erased from disk immediately.",
            u"-----------------------------",
            u"[ Press Select or Back to exit ]"
        ]
        self.viewer_scroll = 0
        self.tab_index = 4
        self.request_repaint()

    def action_show_privacy(self):
        self.viewer_title = u"Privacy Policy"
        self.viewer_lines = [
            u"NULLHOST PRIVACY POLICY",
            u"-----------------------------",
            u"1. Zero Tracking & Telemetry:",
            u"Nullhost does not track IP",
            u"addresses, user agents, or ads.",
            u"",
            u"2. Ephemeral Storage:",
            u"Once a file expires or burns,",
            u"it is permanently scrubbed.",
            u"",
            u"3. Cryptographic Handshake:",
            u"Authentic mobile client tokens",
            u"use local HMAC signatures.",
            u"No device credentials stored.",
            u"-----------------------------",
            u"[ Press Select or Back to exit ]"
        ]
        self.viewer_scroll = 0
        self.tab_index = 4
        self.request_repaint()

    def action_show_about(self):
        self.viewer_title = u"About Nullhost"
        self.viewer_lines = [
            u"NULLHOST S60 KEYPAD",
            u"-----------------------------",
            u"Version: " + APP_VERSION,
            u"Platform: Series 60 (Keypad)",
            u"Target: S60 3rd / 5th Edition",
            u"Hardware: 5-Way D-Pad + CBA",
            u"Qt / WRT: NONE (Pure Native)",
            u"",
            u"ENGINEERING",
            u"Gelaxiz High-Craft Legacy Lab",
            u"Optimized for Nokia N95, E71,",
            u"E72, 6120c, and all S60 phones.",
            u"-----------------------------",
            u"[ Press Select or Back to exit ]"
        ]
        self.viewer_scroll = 0
        self.tab_index = 4
        self.request_repaint()

    # ----------------------------------------------------
    # MOBILE HMAC AUTHENTICATION & MULTIPART UPLOAD
    # ----------------------------------------------------
    def get_auth_token(self):
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
        req.add_header('User-Agent', 'Nullhost-S60-Keypad/1.0')
        req.add_header('Content-Type', 'application/x-www-form-urlencoded')
        
        try:
            resp = urllib2.urlopen(req, timeout=15)
            body = resp.read()
            token = ""
            for part in body.replace('{', '').replace('}', '').split(','):
                kv = part.split(':', 1)
                if len(kv) == 2:
                    k = kv[0].strip(' "\'')
                    v = kv[1].strip(' "\'')
                    if k == 'token': token = v
                    elif k == 'server_time':
                        try:
                            s_time = int(v)
                            self.time_offset = s_time - int(time.time())
                        except Exception: pass
            return token
        except Exception:
            return None

    def action_upload_now(self):
        if not self.selected_file or not os.path.exists(self.selected_file):
            appuifw.note(u"Please select a file first", "error")
            return
            
        appuifw.note(u"Authenticating app...", "info")
        token = self.get_auth_token()
        if not token:
            appuifw.note(u"App auth failed. Check network.", "error")
            return
            
        appuifw.note(u"Uploading file...", "info")
        filename = os.path.basename(self.selected_file)
        boundary = '----NullhostS60Boundary%d' % int(time.time())
        
        parts = []
        def add_field(name, value):
            parts.append('--' + boundary)
            parts.append('Content-Disposition: form-data; name="%s"' % name)
            parts.append('')
            parts.append(str(value))
            
        add_field('format', 'json')
        add_field('expiry', self.opt_expiry)
        if self.opt_burn != "0": add_field('burn_views', self.opt_burn)
        if self.opt_password: add_field('password', self.opt_password)
        if self.opt_custom_id: add_field('custom_id', self.opt_custom_id)
        if self.account_id: add_field('account_id', self.account_id)
        
        parts.append('--' + boundary)
        parts.append('Content-Disposition: form-data; name="file"; filename="%s"' % filename)
        parts.append('Content-Type: application/octet-stream')
        parts.append('')
        
        head = '\r\n'.join(parts) + '\r\n'
        tail = '\r\n--' + boundary + '--\r\n'
        
        try:
            with open(self.selected_file, 'rb') as f:
                content = f.read()
            body = head + content + tail
            
            upload_url = self.server_url.rstrip('/') + '/api/mobile/upload'
            req = urllib2.Request(upload_url, body)
            req.add_header('Content-Type', 'multipart/form-data; boundary=' + boundary)
            req.add_header('X-Nullhost-App-Token', token)
            req.add_header('User-Agent', 'Nullhost-S60-Keypad/1.0')
            if self.account_id: req.add_header('X-Nullhost-Account', self.account_id)
            
            resp = urllib2.urlopen(req, timeout=45)
            resp_body = resp.read()
            
            res_dict = {}
            for item in resp_body.replace('{', '').replace('}', '').split(','):
                kv = item.split(':', 1)
                if len(kv) == 2:
                    k = kv[0].strip(' "\'')
                    v = kv[1].strip(' "\'')
                    res_dict[k] = v
            self.upload_result = res_dict
            
            # Show result popup
            url = res_dict.get('url', '')
            appuifw.note(u"Upload Successful!\nLink: " + unicode(url[:30]), "conf")
            # Offer copy
            appuifw.query(u"Copy link:", "text", unicode(url))
        except Exception as e:
            appuifw.note(u"Upload failed: " + unicode(str(e)[:30]), "error")

    # ----------------------------------------------------
    # DRAWING & S60 KEYPAD UI RENDERING
    # ----------------------------------------------------
    def request_repaint(self):
        if IN_SYMBIAN and hasattr(self, 'canvas'):
            self.canvas.drawNow()

    def handle_redraw(self, rect):
        if not IN_SYMBIAN: return
        img = self.buffer
        img.clear(COLOR_BG)
        
        # S60 Top Title Bar
        img.rectangle((0, 0, self.width, 22), fill=COLOR_HEADER)
        img.text((4, 16), APP_NAME, fill=COLOR_ACCENT, font='title')
        img.text((self.width - 45, 16), time.strftime("%H:%M"), fill=COLOR_TEXT_MUTED, font='dense')
        
        # S60 D-Pad Tab Switcher
        if self.tab_index != 4:
            tab_w = self.width / len(self.tabs)
            for i, t_name in enumerate(self.tabs):
                tx = i * tab_w
                is_active = (i == self.tab_index)
                fill_c = COLOR_TAB_ACTIVE if is_active else COLOR_TAB_BG
                img.rectangle((tx, 22, tx + tab_w, 42), fill=fill_c, outline=COLOR_BORDER)
                img.text((tx + 4, 36), t_name, fill=COLOR_TEXT, font='dense')
                
        # Tab Content
        if self.tab_index == 0:
            self.render_tab_upload(img)
        elif self.tab_index == 1:
            self.render_tab_browser(img)
        elif self.tab_index == 2:
            self.render_tab_gallery(img)
        elif self.tab_index == 3:
            self.render_tab_account(img)
        elif self.tab_index == 4:
            self.render_viewer(img)
            
        self.canvas.blit(img)

    def handle_event(self, ev):
        pass

    def render_tab_upload(self, img):
        items = [
            (u"File", os.path.basename(self.selected_file) if self.selected_file else u"(None selected)"),
            (u"Expiry", unicode(self.opt_expiry)),
            (u"Burn Limit", u"Off" if self.opt_burn == "0" else unicode(self.opt_burn) + u" Views"),
            (u"Password", u"Set" if self.opt_password else u"(None)"),
            (u"Link Slug", unicode(self.opt_custom_id) if self.opt_custom_id else u"(Random)"),
            (u">>> UPLOAD FILE NOW <<<", u"")
        ]
        
        y = 48
        row_h = 28
        for i, (label, val) in enumerate(items):
            is_cursor = (i == self.cursor_upload)
            if is_cursor:
                img.rectangle((2, y, self.width - 2, y + row_h), fill=COLOR_CURSOR)
            else:
                img.rectangle((2, y, self.width - 2, y + row_h), fill=COLOR_ROW_EVEN if i % 2 == 0 else COLOR_ROW_ODD)
                
            if i == 5:
                # Main action row
                img.text((16, y + 18), label, fill=COLOR_TEXT if not is_cursor else (255, 255, 0), font='title')
            else:
                img.text((6, y + 18), label + u":", fill=COLOR_ACCENT if not is_cursor else COLOR_TEXT, font='dense')
                disp_val = val if len(val) < 16 else val[:14] + u".."
                img.text((100, y + 18), unicode(disp_val), fill=COLOR_TEXT, font='dense')
            y += row_h + 2

        # S60 Help Hint at bottom
        img.text((4, self.height - 4), u"D-Pad: Move/Select | Left: Options", fill=COLOR_TEXT_MUTED, font='dense')

    def render_tab_browser(self, img):
        p_str = self.browser_path if len(self.browser_path) < 26 else ".." + self.browser_path[-24:]
        img.text((4, 54), unicode(p_str), fill=COLOR_ACCENT, font='dense')
        
        y = 58
        row_h = 24
        max_rows = (self.height - 70) / row_h
        start = max(0, self.cursor_browser - int(max_rows / 2))
        end = min(len(self.browser_items), start + int(max_rows))
        
        for i in range(start, end):
            name, full, is_dir, sz = self.browser_items[i]
            is_cursor = (i == self.cursor_browser)
            if is_cursor:
                img.rectangle((2, y, self.width - 2, y + row_h), fill=COLOR_CURSOR)
            else:
                img.rectangle((2, y, self.width - 2, y + row_h), fill=COLOR_ROW_EVEN if i % 2 == 0 else COLOR_ROW_ODD)
                
            prefix = u"[*] " if is_dir else u"[ ] "
            disp_name = name if len(name) < 22 else name[:20] + u".."
            img.text((4, y + 16), prefix + unicode(disp_name), fill=COLOR_TEXT, font='dense')
            y += row_h + 1

    def render_tab_gallery(self, img):
        img.text((4, 54), u"Local Photos Scan:", fill=COLOR_ACCENT, font='dense')
        if not self.gallery_items:
            img.text((8, 76), u"Scanning E:\\Images...", fill=COLOR_TEXT_MUTED, font='dense')
            return
            
        y = 58
        row_h = 24
        max_rows = (self.height - 70) / row_h
        start = max(0, self.cursor_gallery - int(max_rows / 2))
        end = min(len(self.gallery_items), start + int(max_rows))
        
        for i in range(start, end):
            fname, full, sz = self.gallery_items[i]
            is_cursor = (i == self.cursor_gallery)
            if is_cursor:
                img.rectangle((2, y, self.width - 2, y + row_h), fill=COLOR_CURSOR)
            else:
                img.rectangle((2, y, self.width - 2, y + row_h), fill=COLOR_ROW_EVEN if i % 2 == 0 else COLOR_ROW_ODD)
            disp = fname if len(fname) < 20 else fname[:18] + u".."
            img.text((4, y + 16), unicode(disp), fill=COLOR_TEXT, font='dense')
            sz_str = u"%.0f KB" % (sz / 1024.0)
            img.text((self.width - 55, y + 16), sz_str, fill=COLOR_TEXT_MUTED, font='dense')
            y += row_h + 1

    def render_tab_account(self, img):
        img.text((6, 56), u"Account Settings", fill=COLOR_ACCENT, font='title')
        img.text((6, 80), u"Account ID (24-digit):", fill=COLOR_TEXT_MUTED, font='dense')
        aid_disp = self.account_id if self.account_id else u"(Not configured)"
        img.text((6, 102), unicode(aid_disp), fill=COLOR_TEXT, font='title')
        
        img.rectangle((4, 120, self.width - 4, 150), fill=COLOR_CURSOR)
        img.text((20, 140), u"Press Select to Change", fill=COLOR_TEXT, font='dense')
        
        img.text((6, 180), u"Server URL:", fill=COLOR_TEXT_MUTED, font='dense')
        img.text((6, 202), unicode(self.server_url), fill=COLOR_TEXT, font='dense')

    def render_viewer(self, img):
        img.rectangle((0, 0, self.width, 24), fill=COLOR_HEADER)
        img.text((4, 18), self.viewer_title, fill=COLOR_ACCENT, font='title')
        
        y = 40
        start = self.viewer_scroll
        for line in self.viewer_lines[start:start+12]:
            img.text((6, y), line, fill=COLOR_TEXT, font='dense')
            y += 18

def main():
    app = S60KeypadApp()
    if IN_SYMBIAN:
        lock = e32.Ao_lock()
        app.lock = lock
        lock.wait()

if __name__ == '__main__':
    main()
