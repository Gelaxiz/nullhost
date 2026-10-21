package org.gelaxiz.nullhost;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.*;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

/**
 * Nullhost Universal Java ME (MIDP 2.0 / CLDC 1.1 / JSR-75) Client.
 * Standalone application with full keypad & touch support.
 * Copyright (c) 2026 Gelaxiz. All rights reserved.
 */
public class NullhostMIDlet extends MIDlet implements CommandListener {

    private static final String APP_VERSION = "1.1.0";
    private static final String CLIENT_ID = "j2me-universal";
    private static final String MOBILE_SECRET = "nullhost_secret_mobile_app_key_2026";
    private static final String DEFAULT_SERVER = "https://nullhost.cc";

    private Display display;

    // Configurable state
    private String serverUrl = DEFAULT_SERVER;
    private String accountId = "";
    private String selectedFilePath = null;
    private long selectedFileSize = 0;
    private long timeOffset = 0;

    // UI Forms
    private Form uploadForm;
    private StringItem fileStatusItem;
    private ChoiceGroup expiryChoice;
    private ChoiceGroup burnChoice;
    private TextField passwordField;
    private TextField slugField;
    private TextField accountField;

    // Commands
    private Command cmdUpload;
    private Command cmdBrowse;
    private Command cmdGallery;
    private Command cmdSettings;
    private Command cmdTos;
    private Command cmdPrivacy;
    private Command cmdAbout;
    private Command cmdExit;
    private Command cmdBack;
    private Command cmdSelect;
    private Command cmdSaveSettings;

    // Browser State
    private List browserList;
    private String currentDirectoryUrl = null;
    private Vector currentEntries = new Vector();

    // Gallery State
    private List galleryList;
    private Vector galleryEntries = new Vector();

    public void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            initCommands();
            initUploadForm();
        }
        display.setCurrent(uploadForm);
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {}

    private void initCommands() {
        cmdUpload = new Command("Upload File", Command.OK, 1);
        cmdBrowse = new Command("Browse Files", Command.SCREEN, 2);
        cmdGallery = new Command("Photo Gallery", Command.SCREEN, 3);
        cmdSettings = new Command("Settings", Command.SCREEN, 4);
        cmdTos = new Command("Terms of Service", Command.SCREEN, 5);
        cmdPrivacy = new Command("Privacy Policy", Command.SCREEN, 6);
        cmdAbout = new Command("About", Command.SCREEN, 7);
        cmdExit = new Command("Exit", Command.EXIT, 8);
        cmdBack = new Command("Back", Command.BACK, 1);
        cmdSelect = new Command("Select", Command.OK, 1);
        cmdSaveSettings = new Command("Save", Command.OK, 1);
    }

    private void initUploadForm() {
        uploadForm = new Form("Nullhost Mobile");

        fileStatusItem = new StringItem("Selected File:", "(None selected - use Browse or Gallery)");
        uploadForm.append(fileStatusItem);

        String[] expiryLabels = {"1 Hour", "24 Hours (Default)", "7 Days", "30 Days", "Never Expire"};
        expiryChoice = new ChoiceGroup("Expiry:", Choice.EXCLUSIVE, expiryLabels, null);
        expiryChoice.setSelectedIndex(1, true); // 24 Hours
        uploadForm.append(expiryChoice);

        String[] burnLabels = {"Disabled (Normal)", "1 View (Burn)", "5 Views", "10 Views"};
        burnChoice = new ChoiceGroup("Burn After Reading:", Choice.EXCLUSIVE, burnLabels, null);
        burnChoice.setSelectedIndex(0, true);
        uploadForm.append(burnChoice);

        passwordField = new TextField("Password Protection (Optional):", "", 32, TextField.ANY);
        uploadForm.append(passwordField);

        slugField = new TextField("Custom Slug (Optional):", "", 32, TextField.ANY);
        uploadForm.append(slugField);

        accountField = new TextField("Account ID (24 digits):", accountId, 24, TextField.NUMERIC);
        uploadForm.append(accountField);

        uploadForm.addCommand(cmdUpload);
        uploadForm.addCommand(cmdBrowse);
        uploadForm.addCommand(cmdGallery);
        uploadForm.addCommand(cmdSettings);
        uploadForm.addCommand(cmdTos);
        uploadForm.addCommand(cmdPrivacy);
        uploadForm.addCommand(cmdAbout);
        uploadForm.addCommand(cmdExit);
        uploadForm.setCommandListener(this);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == cmdUpload) {
            startUploadThread();
        } else if (c == cmdBrowse) {
            openFileBrowser(null);
        } else if (c == cmdGallery) {
            openGallery();
        } else if (c == cmdSettings) {
            openSettings();
        } else if (c == cmdTos) {
            showTos();
        } else if (c == cmdPrivacy) {
            showPrivacy();
        } else if (c == cmdAbout) {
            showAbout();
        } else if (c == cmdBack) {
            if (d == browserList) {
                handleBrowserBack();
            } else {
                display.setCurrent(uploadForm);
            }
        } else if (c == cmdSelect && d == browserList) {
            handleBrowserSelect();
        } else if (c == List.SELECT_COMMAND && d == browserList) {
            handleBrowserSelect();
        } else if (c == cmdSelect && d == galleryList) {
            handleGallerySelect();
        } else if (c == List.SELECT_COMMAND && d == galleryList) {
            handleGallerySelect();
        } else if (c == cmdSaveSettings) {
            saveSettings();
        }
    }

    // ------------------------------------------------------------------------
    // FILE BROWSER (JSR-75 FileConnection)
    // ------------------------------------------------------------------------
    private void openFileBrowser(String dirUrl) {
        currentEntries.removeAllElements();
        currentDirectoryUrl = dirUrl;

        browserList = new List(dirUrl == null ? "Select Storage Root" : "Browse Files", List.IMPLICIT);
        browserList.addCommand(cmdSelect);
        browserList.addCommand(cmdBack);
        browserList.setCommandListener(this);

        try {
            if (dirUrl == null) {
                Enumeration roots = FileSystemRegistry.listRoots();
                while (roots.hasMoreElements()) {
                    String root = (String) roots.nextElement();
                    currentEntries.addElement("file:///" + root);
                    browserList.append("[Drive] " + root, null);
                }
            } else {
                FileConnection fc = (FileConnection) Connector.open(dirUrl, Connector.READ);
                if (fc.isDirectory()) {
                    Enumeration list = fc.list();
                    while (list.hasMoreElements()) {
                        String name = (String) list.nextElement();
                        currentEntries.addElement(dirUrl + name);
                        if (name.endsWith("/")) {
                            browserList.append("[DIR] " + name, null);
                        } else {
                            browserList.append(name, null);
                        }
                    }
                }
                fc.close();
            }
        } catch (Exception e) {
            showAlert("Error", "Storage access failed: " + e.getMessage(), AlertType.ERROR, uploadForm);
            return;
        }

        display.setCurrent(browserList);
    }

    private void handleBrowserSelect() {
        int idx = browserList.getSelectedIndex();
        if (idx < 0 || idx >= currentEntries.size()) return;

        String selectedUrl = (String) currentEntries.elementAt(idx);
        if (selectedUrl.endsWith("/")) {
            openFileBrowser(selectedUrl);
        } else {
            selectedFilePath = selectedUrl;
            try {
                FileConnection fc = (FileConnection) Connector.open(selectedUrl, Connector.READ);
                selectedFileSize = fc.fileSize();
                fc.close();
            } catch (Exception e) {
                selectedFileSize = 0;
            }
            String fname = selectedUrl.substring(selectedUrl.lastIndexOf('/') + 1);
            fileStatusItem.setText(fname + " (" + (selectedFileSize / 1024) + " KB)");
            display.setCurrent(uploadForm);
            showAlert("File Selected", fname + " is ready to upload.", AlertType.INFO, uploadForm);
        }
    }

    private void handleBrowserBack() {
        if (currentDirectoryUrl == null) {
            display.setCurrent(uploadForm);
            return;
        }
        String trimmed = currentDirectoryUrl.endsWith("/") ? currentDirectoryUrl.substring(0, currentDirectoryUrl.length() - 1) : currentDirectoryUrl;
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash > 8) {
            String parent = trimmed.substring(0, lastSlash + 1);
            openFileBrowser(parent);
        } else {
            openFileBrowser(null);
        }
    }

    // ------------------------------------------------------------------------
    // GALLERY (JSR-75 Photo Scans)
    // ------------------------------------------------------------------------
    private void openGallery() {
        galleryEntries.removeAllElements();
        galleryList = new List("Photo Gallery", List.IMPLICIT);
        galleryList.addCommand(cmdSelect);
        galleryList.addCommand(cmdBack);
        galleryList.setCommandListener(this);

        try {
            Enumeration roots = FileSystemRegistry.listRoots();
            while (roots.hasMoreElements()) {
                String root = (String) roots.nextElement();
                scanDirectoryForImages("file:///" + root, 0);
            }
        } catch (Exception e) {}

        if (galleryEntries.isEmpty()) {
            showAlert("Gallery", "No images (.jpg, .png, .gif) found on device memory.", AlertType.INFO, uploadForm);
            return;
        }

        display.setCurrent(galleryList);
    }

    private void scanDirectoryForImages(String dirUrl, int depth) {
        if (depth > 3 || galleryEntries.size() > 50) return;
        try {
            FileConnection fc = (FileConnection) Connector.open(dirUrl, Connector.READ);
            if (fc.isDirectory()) {
                Enumeration list = fc.list();
                while (list.hasMoreElements()) {
                    String name = (String) list.nextElement();
                    String full = dirUrl + name;
                    if (name.endsWith("/")) {
                        String lower = name.toLowerCase();
                        if (lower.indexOf("image") >= 0 || lower.indexOf("photo") >= 0 || lower.indexOf("dcim") >= 0 || lower.indexOf("picture") >= 0) {
                            scanDirectoryForImages(full, depth + 1);
                        }
                    } else {
                        String lower = name.toLowerCase();
                        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) {
                            galleryEntries.addElement(full);
                            galleryList.append(name, null);
                        }
                    }
                }
            }
            fc.close();
        } catch (Exception e) {}
    }

    private void handleGallerySelect() {
        int idx = galleryList.getSelectedIndex();
        if (idx < 0 || idx >= galleryEntries.size()) return;

        String selectedUrl = (String) galleryEntries.elementAt(idx);
        selectedFilePath = selectedUrl;
        try {
            FileConnection fc = (FileConnection) Connector.open(selectedUrl, Connector.READ);
            selectedFileSize = fc.fileSize();
            fc.close();
        } catch (Exception e) {
            selectedFileSize = 0;
        }
        String fname = selectedUrl.substring(selectedUrl.lastIndexOf('/') + 1);
        fileStatusItem.setText(fname + " (" + (selectedFileSize / 1024) + " KB)");
        display.setCurrent(uploadForm);
        showAlert("Photo Selected", fname + " is ready for upload.", AlertType.INFO, uploadForm);
    }

    // ------------------------------------------------------------------------
    // SETTINGS & MENU VIEWS (English Only)
    // ------------------------------------------------------------------------
    private TextField settingServerField;
    private TextField settingAccountField;

    private void openSettings() {
        Form f = new Form("Settings");
        settingServerField = new TextField("Server URL:", serverUrl, 128, TextField.URL);
        settingAccountField = new TextField("Account ID (24 digits):", accountId, 24, TextField.NUMERIC);
        f.append(settingServerField);
        f.append(settingAccountField);
        f.addCommand(cmdSaveSettings);
        f.addCommand(cmdBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    private void saveSettings() {
        serverUrl = settingServerField.getString().trim();
        if (serverUrl.endsWith("/")) serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        accountId = settingAccountField.getString().trim();
        accountField.setString(accountId);
        showAlert("Settings Saved", "Server: " + serverUrl + "\nAccount: " + (accountId.length() > 0 ? accountId : "None"), AlertType.CONFIRMATION, uploadForm);
    }

    private void showTos() {
        showTextScreen("Terms of Service",
            "NULLHOST TERMS OF SERVICE\n" +
            "-----------------------------------\n" +
            "Last updated: October 2026\n\n" +
            "1. ACCEPTABLE USE\n" +
            "Nullhost provides privacy-first ephemeral and permanent file storage. " +
            "Users must NOT upload malicious code, exploits, CSAM, copyright infringement, " +
            "or illegal materials.\n\n" +
            "2. ZERO TOLERANCE\n" +
            "Violating files are terminated immediately without prior notice.\n\n" +
            "3. RETENTION\n" +
            "Files set to expire or burn are purged immediately and permanently.\n\n" +
            "4. DISCLAIMER\n" +
            "Provided AS-IS without warranty."
        );
    }

    private void showPrivacy() {
        showTextScreen("Privacy Policy",
            "NULLHOST PRIVACY POLICY\n" +
            "-----------------------------------\n" +
            "Last updated: October 2026\n\n" +
            "1. ZERO TRACKING\n" +
            "Nullhost does NOT collect user tracking cookies, IP telemetry, or device identifiers.\n\n" +
            "2. EPHEMERAL STORAGE\n" +
            "Files reaching their expiration date or burn count are purged immediately.\n\n" +
            "3. AUTHENTIC APP SECURITY\n" +
            "Mobile requests use local HMAC cryptographic handshakes without transmitting sensitive passwords."
        );
    }

    private void showAbout() {
        long totalMem = Runtime.getRuntime().totalMemory() / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024;
        showTextScreen("About Nullhost",
            "NULLHOST UNIVERSAL J2ME\n" +
            "-----------------------------------\n" +
            "Version: " + APP_VERSION + "\n" +
            "Platform: Java ME MIDP 2.0 / CLDC 1.1\n" +
            "Storage Engine: JSR-75 FileConnection\n" +
            "Dependencies: ZERO (Pure Native J2ME)\n\n" +
            "DEVICE STATUS:\n" +
            "JVM Memory: " + freeMem + " KB free / " + totalMem + " KB total\n" +
            "Screen: " + display.getCurrent().getWidth() + "x" + display.getCurrent().getHeight() + "\n" +
            "Microedition Platform: " + System.getProperty("microedition.platform") + "\n\n" +
            "ENGINEERING:\n" +
            "Crafted by Gelaxiz Labs (c) 2026\n" +
            "Licensed for public self-hosting."
        );
    }

    private void showTextScreen(String title, String body) {
        Form f = new Form(title);
        f.append(new StringItem("", body));
        f.addCommand(cmdBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    private void showAlert(String title, String msg, AlertType type, Displayable next) {
        Alert alert = new Alert(title, msg, null, type);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }

    // ------------------------------------------------------------------------
    // AUTHENTICATION & MULTIPART UPLOAD THREAD
    // ------------------------------------------------------------------------
    private void startUploadThread() {
        if (selectedFilePath == null) {
            showAlert("Select File", "Please select a file using Browse or Gallery first.", AlertType.WARNING, uploadForm);
            return;
        }

        Alert progressAlert = new Alert("Uploading...", "Authenticating app with Nullhost server...", null, AlertType.INFO);
        progressAlert.setTimeout(Alert.FOREVER);
        display.setCurrent(progressAlert);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    // 1. Authenticate authentic app
                    String token = authenticateApp();
                    if (token == null) {
                        showAlert("Auth Failed", "Could not verify app credentials with server.", AlertType.ERROR, uploadForm);
                        return;
                    }

                    // 2. Perform multipart upload
                    String resultUrl = uploadFileWithToken(token);
                    if (resultUrl != null) {
                        Form resultForm = new Form("Upload Succeeded");
                        resultForm.append(new StringItem("Short URL:", resultUrl));
                        resultForm.append(new StringItem("Raw URL:", resultUrl.replace("/v/", "/raw/")));
                        resultForm.addCommand(cmdBack);
                        resultForm.setCommandListener(NullhostMIDlet.this);
                        display.setCurrent(resultForm);
                    } else {
                        showAlert("Upload Failed", "Server returned an error or timeout.", AlertType.ERROR, uploadForm);
                    }
                } catch (Exception e) {
                    showAlert("Upload Error", e.getMessage() != null ? e.getMessage() : e.toString(), AlertType.ERROR, uploadForm);
                }
            }
        });
        t.start();
    }

    private String authenticateApp() throws Exception {
        long ts = (System.currentTimeMillis() / 1000L) + timeOffset;
        String nonce = Long.toString(System.currentTimeMillis(), 16) + "abcd";
        String payload = CLIENT_ID + ":" + ts + ":" + nonce;
        String signature = CryptoHelper.hmacSha256Hex(MOBILE_SECRET, payload);

        String postBody = "client=" + CLIENT_ID +
                          "&version=" + APP_VERSION +
                          "&timestamp=" + ts +
                          "&nonce=" + nonce +
                          "&signature=" + signature;

        HttpConnection conn = (HttpConnection) Connector.open(serverUrl + "/api/mobile/auth", Connector.READ_WRITE);
        conn.setRequestMethod(HttpConnection.POST);
        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        OutputStream os = conn.openOutputStream();
        os.write(postBody.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        if (code != 200) {
            conn.close();
            return null;
        }

        InputStream is = conn.openInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int len;
        while ((len = is.read(buf)) != -1) {
            baos.write(buf, 0, len);
        }
        is.close();
        conn.close();

        String respStr = new String(baos.toByteArray(), "UTF-8");
        // Extract token and server_time
        String token = extractJsonField(respStr, "token");
        String sTime = extractJsonField(respStr, "server_time");
        if (sTime != null) {
            try {
                timeOffset = Long.parseLong(sTime) - (System.currentTimeMillis() / 1000L);
            } catch (Exception ex) {}
        }
        return token;
    }

    private String uploadFileWithToken(String token) throws Exception {
        String[] expiryVals = {"1h", "24h", "7d", "30d", "never"};
        String expiry = expiryVals[expiryChoice.getSelectedIndex()];

        String[] burnVals = {"0", "1", "5", "10"};
        String burn = burnVals[burnChoice.getSelectedIndex()];

        String password = passwordField.getString().trim();
        String slug = slugField.getString().trim();
        String accId = accountField.getString().trim();

        String boundary = "----NullhostJ2meBoundary" + System.currentTimeMillis();
        String fname = selectedFilePath.substring(selectedFilePath.lastIndexOf('/') + 1);

        HttpConnection conn = (HttpConnection) Connector.open(serverUrl + "/api/mobile/upload", Connector.READ_WRITE);
        conn.setRequestMethod(HttpConnection.POST);
        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("X-Nullhost-App-Token", token);
        if (accId.length() > 0) {
            conn.setRequestProperty("X-Nullhost-Account", accId);
        }

        OutputStream os = conn.openOutputStream();

        // Write form fields
        writeMultipartField(os, boundary, "format", "json");
        writeMultipartField(os, boundary, "expiry", expiry);
        if (!burn.equals("0")) {
            writeMultipartField(os, boundary, "burn_views", burn);
        }
        if (password.length() > 0) {
            writeMultipartField(os, boundary, "password", password);
        }
        if (slug.length() > 0) {
            writeMultipartField(os, boundary, "custom_id", slug);
        }
        if (accId.length() > 0) {
            writeMultipartField(os, boundary, "account_id", accId);
        }

        // File header
        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\n").getBytes("UTF-8"));
        os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));

        // File content
        FileConnection fc = (FileConnection) Connector.open(selectedFilePath, Connector.READ);
        InputStream fis = fc.openInputStream();
        byte[] fbuf = new byte[2048];
        int flen;
        while ((flen = fis.read(fbuf)) != -1) {
            os.write(fbuf, 0, flen);
        }
        fis.close();
        fc.close();

        // End boundary
        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();

        int respCode = conn.getResponseCode();
        InputStream is = conn.openInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] rBuf = new byte[256];
        int rLen;
        while ((rLen = is.read(rBuf)) != -1) {
            baos.write(rBuf, 0, rLen);
        }
        is.close();
        conn.close();

        if (respCode == 200 || respCode == 201) {
            String respStr = new String(baos.toByteArray(), "UTF-8");
            String url = extractJsonField(respStr, "url");
            return url != null ? url : serverUrl;
        }
        return null;
    }

    private void writeMultipartField(OutputStream os, String boundary, String name, String val) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        os.write((val + "\r\n").getBytes("UTF-8"));
    }

    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) {
            key = field;
            idx = json.indexOf(key);
            if (idx == -1) return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon == -1) return null;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\"')) {
            start++;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != '\"' && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end).trim();
    }
}
