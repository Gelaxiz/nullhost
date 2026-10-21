package org.gelaxiz.nullhost;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * Simplified, direct, lightning-fast uploader for Symbian Belle, S60, and J2ME phones.
 * Pure Java 1.4 (Major version 48) - Zero Python, Zero bloated dependencies.
 * Copyright (c) 2026 Gelaxiz. All rights reserved.
 */
public class NullhostMIDlet extends MIDlet implements CommandListener, ItemCommandListener {

    private static final String APP_VERSION = "1.2.0";
    private static final String CLIENT_ID = "j2me-universal";
    private static final String MOBILE_SECRET = "nullhost_secret_mobile_app_key_2026";
    private static final String DEFAULT_SERVER = "http://nullhost.gelaxiz.xyz";

    private Display display;

    // Configurable state
    private String serverUrl = DEFAULT_SERVER;
    private String accountId = "";
    private int expiryIndex = 1; // 24 Hours default
    private int burnIndex = 0;   // Disabled default
    private String customPassword = "";
    private String customSlug = "";

    // Selected file state
    private String selectedFilePath = null;
    private long selectedFileSize = 0;

    // Main Form
    private Form mainForm;
    private StringItem fileStatusItem;
    private StringItem btnSelectPhoto;
    private StringItem btnBrowseFiles;
    private StringItem btnUploadNow;
    private StringItem tipItem;

    // Sub-Forms
    private Form optionsForm;
    private ChoiceGroup optExpiry;
    private ChoiceGroup optBurn;
    private TextField optPassword;
    private TextField optSlug;
    private TextField optAccount;

    private Form serverForm;
    private TextField optServerUrl;

    // Commands
    private Command cmdUpload;
    private Command cmdPhoto;
    private Command cmdBrowse;
    private Command cmdNativeGallery;
    private Command cmdOptions;
    private Command cmdServerSettings;
    private Command cmdAbout;
    private Command cmdExit;
    private Command cmdBack;
    private Command cmdSelect;
    private Command cmdSaveOptions;
    private Command cmdSaveServer;

    // Item Action Command
    private Command cmdItemAction;

    // File Browser State
    private List browserList;
    private String currentDirectoryUrl = null;
    private Vector currentEntries = new Vector();

    // Photo Gallery State
    private List galleryList;
    private Vector galleryEntries = new Vector();

    public void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            initCommands();
            initMainForm();
        }
        display.setCurrent(mainForm);
    }

    public void pauseApp() {}

    public void destroyApp(boolean unconditional) {}

    private void initCommands() {
        cmdUpload = new Command("Upload Now", Command.OK, 1);
        cmdPhoto = new Command("Choose Photo", Command.SCREEN, 2);
        cmdBrowse = new Command("Browse Files", Command.SCREEN, 3);
        cmdNativeGallery = new Command("Native Gallery (Web)", Command.SCREEN, 4);
        cmdOptions = new Command("Upload Options", Command.SCREEN, 5);
        cmdServerSettings = new Command("Server Settings", Command.SCREEN, 6);
        cmdAbout = new Command("About", Command.SCREEN, 7);
        cmdExit = new Command("Exit", Command.EXIT, 8);

        cmdBack = new Command("Back", Command.BACK, 1);
        cmdSelect = new Command("Select", Command.OK, 1);
        cmdSaveOptions = new Command("Save", Command.OK, 1);
        cmdSaveServer = new Command("Save", Command.OK, 1);

        cmdItemAction = new Command("Go", Command.ITEM, 1);
    }

    private void initMainForm() {
        mainForm = new Form("Nullhost");

        fileStatusItem = new StringItem("Selected File:", "(None - choose photo or file below)");
        mainForm.append(fileStatusItem);

        btnSelectPhoto = new StringItem(null, ">> Choose Photo (Gallery)", Item.HYPERLINK);
        btnSelectPhoto.setDefaultCommand(cmdItemAction);
        btnSelectPhoto.setItemCommandListener(this);
        mainForm.append(btnSelectPhoto);

        btnBrowseFiles = new StringItem(null, ">> Browse All Files", Item.HYPERLINK);
        btnBrowseFiles.setDefaultCommand(cmdItemAction);
        btnBrowseFiles.setItemCommandListener(this);
        mainForm.append(btnBrowseFiles);

        btnUploadNow = new StringItem(null, "[ UPLOAD FILE NOW ]", Item.BUTTON);
        btnUploadNow.setDefaultCommand(cmdItemAction);
        btnUploadNow.setItemCommandListener(this);
        mainForm.append(btnUploadNow);

        tipItem = new StringItem("Tip:", "For Symbian Belle system gallery picker, choose 'Native Gallery (Web)' from Options menu.");
        mainForm.append(tipItem);

        mainForm.addCommand(cmdUpload);
        mainForm.addCommand(cmdPhoto);
        mainForm.addCommand(cmdBrowse);
        mainForm.addCommand(cmdNativeGallery);
        mainForm.addCommand(cmdOptions);
        mainForm.addCommand(cmdServerSettings);
        mainForm.addCommand(cmdAbout);
        mainForm.addCommand(cmdExit);
        mainForm.setCommandListener(this);
    }

    private void updateFileStatus() {
        if (selectedFilePath == null) {
            fileStatusItem.setText("(None - choose photo or file below)");
        } else {
            String fname = selectedFilePath.substring(selectedFilePath.lastIndexOf('/') + 1);
            fileStatusItem.setText(fname + " (" + formatSize(selectedFileSize) + ")");
        }
    }

    public void commandAction(Command c, Item item) {
        if (item == btnSelectPhoto) {
            openPhotoGallery();
        } else if (item == btnBrowseFiles) {
            openFileBrowser(null);
        } else if (item == btnUploadNow) {
            startUploadThread();
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == cmdUpload) {
            startUploadThread();
        } else if (c == cmdPhoto) {
            openPhotoGallery();
        } else if (c == cmdBrowse) {
            openFileBrowser(null);
        } else if (c == cmdNativeGallery) {
            openNativeGalleryWeb();
        } else if (c == cmdOptions) {
            openOptionsForm();
        } else if (c == cmdServerSettings) {
            openServerForm();
        } else if (c == cmdAbout) {
            showAbout();
        } else if (c == cmdBack) {
            if (d == browserList) {
                handleBrowserBack();
            } else {
                display.setCurrent(mainForm);
            }
        } else if (c == cmdSelect && d == browserList) {
            handleBrowserSelect();
        } else if (c == List.SELECT_COMMAND && d == browserList) {
            handleBrowserSelect();
        } else if (c == cmdSelect && d == galleryList) {
            handleGallerySelect();
        } else if (c == List.SELECT_COMMAND && d == galleryList) {
            handleGallerySelect();
        } else if (c == cmdSaveOptions) {
            saveOptions();
        } else if (c == cmdSaveServer) {
            saveServer();
        }
    }

    // ------------------------------------------------------------------------
    // NATIVE BELLE WEB LAUNCHER (SYSTEM GALLERY PICKER)
    // ------------------------------------------------------------------------
    private void openNativeGalleryWeb() {
        try {
            String liteUrl = serverUrl;
            if (liteUrl.endsWith("/")) {
                liteUrl = liteUrl.substring(0, liteUrl.length() - 1);
            }
            liteUrl += "/lite";
            boolean needExit = platformRequest(liteUrl);
            if (needExit) {
                destroyApp(true);
                notifyDestroyed();
            }
        } catch (Exception e) {
            showAlert("Browser Error", "Could not launch native browser: " + e.getMessage(), AlertType.ERROR, mainForm);
        }
    }

    // ------------------------------------------------------------------------
    // PHOTO GALLERY (FAST DIRECT DIRECTORY SCAN)
    // ------------------------------------------------------------------------
    private void openPhotoGallery() {
        galleryEntries.removeAllElements();
        galleryList = new List("Choose Photo", List.IMPLICIT);
        galleryList.addCommand(cmdSelect);
        galleryList.addCommand(cmdBack);
        galleryList.setCommandListener(this);

        // Scan primary camera directories on Symbian and phone storage
        String[] standardPaths = {
            "file:///E:/DCIM/100NOKIA/",
            "file:///E:/DCIM/",
            "file:///E:/Images/",
            "file:///C:/Data/Images/",
            "file:///F:/DCIM/100NOKIA/",
            "file:///F:/DCIM/",
            "file:///F:/Images/"
        };

        for (int i = 0; i < standardPaths.length; i++) {
            scanDirectoryForPhotos(standardPaths[i], 1);
            if (galleryEntries.size() >= 40) break;
        }

        // If no images found in standard folders, scan drive roots
        if (galleryEntries.isEmpty()) {
            try {
                Enumeration roots = FileSystemRegistry.listRoots();
                while (roots.hasMoreElements()) {
                    String root = (String) roots.nextElement();
                    scanDirectoryForPhotos("file:///" + root, 0);
                    if (galleryEntries.size() >= 40) break;
                }
            } catch (Exception ex) {}
        }

        if (galleryEntries.isEmpty()) {
            showAlert("No Photos Found", "No images (.jpg, .png, .gif) found in camera folders.", AlertType.INFO, mainForm);
            return;
        }

        display.setCurrent(galleryList);
    }

    private void scanDirectoryForPhotos(String dirUrl, int depth) {
        if (depth > 2 || galleryEntries.size() >= 50) return;
        try {
            FileConnection fc = (FileConnection) Connector.open(dirUrl, Connector.READ);
            if (fc.isDirectory()) {
                Enumeration list = fc.list();
                while (list.hasMoreElements()) {
                    String name = (String) list.nextElement();
                    String full = dirUrl + name;
                    if (name.endsWith("/")) {
                        String lower = name.toLowerCase();
                        if (lower.indexOf("dcim") != -1 || lower.indexOf("100nokia") != -1 || lower.indexOf("camera") != -1 || lower.indexOf("photo") != -1 || lower.indexOf("image") != -1) {
                            scanDirectoryForPhotos(full, depth + 1);
                        }
                    } else {
                        String lower = name.toLowerCase();
                        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) {
                            galleryEntries.addElement(full);
                            long size = 0;
                            try {
                                FileConnection ifc = (FileConnection) Connector.open(full, Connector.READ);
                                size = ifc.fileSize();
                                ifc.close();
                            } catch (Exception e) {}
                            galleryList.append(name + " (" + formatSize(size) + ")", null);
                            if (galleryEntries.size() >= 50) break;
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

        updateFileStatus();
        display.setCurrent(mainForm);
    }

    // ------------------------------------------------------------------------
    // FILE BROWSER (JSR-75 FileConnection)
    // ------------------------------------------------------------------------
    private void openFileBrowser(String dirUrl) {
        currentEntries.removeAllElements();
        currentDirectoryUrl = dirUrl;

        browserList = new List(dirUrl == null ? "Select Drive" : "Browse Files", List.IMPLICIT);
        browserList.addCommand(cmdSelect);
        browserList.addCommand(cmdBack);
        browserList.setCommandListener(this);

        try {
            if (dirUrl == null) {
                Enumeration roots = FileSystemRegistry.listRoots();
                while (roots.hasMoreElements()) {
                    String root = (String) roots.nextElement();
                    currentEntries.addElement("file:///" + root);
                    String driveDesc = formatDriveLabel(root);
                    browserList.append(driveDesc, null);
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
            showAlert("Error", "Storage access failed: " + e.getMessage(), AlertType.ERROR, mainForm);
            return;
        }

        display.setCurrent(browserList);
    }

    private String formatDriveLabel(String root) {
        if (root.startsWith("C:") || root.startsWith("c:")) return "[C:] Phone Memory";
        if (root.startsWith("E:") || root.startsWith("e:")) return "[E:] Mass Memory";
        if (root.startsWith("F:") || root.startsWith("f:")) return "[F:] Memory Card";
        if (root.startsWith("D:") || root.startsWith("d:")) return "[D:] RAM Disk";
        if (root.startsWith("Z:") || root.startsWith("z:")) return "[Z:] ROM";
        return "[Drive] " + root;
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
            updateFileStatus();
            display.setCurrent(mainForm);
        }
    }

    private void handleBrowserBack() {
        if (currentDirectoryUrl == null) {
            display.setCurrent(mainForm);
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
    // UPLOAD OPTIONS & SERVER SETTINGS
    // ------------------------------------------------------------------------
    private void openOptionsForm() {
        optionsForm = new Form("Upload Options");

        String[] expiryLabels = {"1 Hour", "24 Hours (Default)", "7 Days", "30 Days", "Never Expire"};
        optExpiry = new ChoiceGroup("Retention Time:", Choice.EXCLUSIVE, expiryLabels, null);
        optExpiry.setSelectedIndex(expiryIndex, true);
        optionsForm.append(optExpiry);

        String[] burnLabels = {"Disabled (Normal)", "1 View (Burn)", "5 Views", "10 Views"};
        optBurn = new ChoiceGroup("Burn After Reading:", Choice.EXCLUSIVE, burnLabels, null);
        optBurn.setSelectedIndex(burnIndex, true);
        optionsForm.append(optBurn);

        optPassword = new TextField("Password (Optional):", customPassword, 32, TextField.ANY);
        optionsForm.append(optPassword);

        optSlug = new TextField("Custom Link / Slug:", customSlug, 32, TextField.ANY);
        optionsForm.append(optSlug);

        optAccount = new TextField("Account ID (24 digits):", accountId, 24, TextField.NUMERIC);
        optionsForm.append(optAccount);

        optionsForm.addCommand(cmdSaveOptions);
        optionsForm.addCommand(cmdBack);
        optionsForm.setCommandListener(this);

        display.setCurrent(optionsForm);
    }

    private void saveOptions() {
        expiryIndex = optExpiry.getSelectedIndex();
        burnIndex = optBurn.getSelectedIndex();
        customPassword = optPassword.getString().trim();
        customSlug = optSlug.getString().trim();
        accountId = optAccount.getString().trim();

        display.setCurrent(mainForm);
    }

    private void openServerForm() {
        serverForm = new Form("Server Settings");
        optServerUrl = new TextField("Server URL:", serverUrl, 128, TextField.URL);
        serverForm.append(optServerUrl);

        serverForm.addCommand(cmdSaveServer);
        serverForm.addCommand(cmdBack);
        serverForm.setCommandListener(this);

        display.setCurrent(serverForm);
    }

    private void saveServer() {
        String entered = optServerUrl.getString().trim();
        if (entered.endsWith("/")) {
            entered = entered.substring(0, entered.length() - 1);
        }
        if (entered.length() > 0) {
            serverUrl = entered;
        }
        display.setCurrent(mainForm);
    }

    private void showAbout() {
        long totalMem = Runtime.getRuntime().totalMemory() / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024;

        Form f = new Form("About Nullhost");
        f.append(new StringItem("Nullhost Universal J2ME", "Version " + APP_VERSION + "\n\n" +
            "Streamlined direct uploader for Symbian Belle, Series 60, and J2ME phones.\n\n" +
            "Zero Python dependencies.\n" +
            "Direct HMAC mobile authentication.\n" +
            "Native Symbian Web Lite support.\n\n" +
            "Server: " + serverUrl + "\n" +
            "Free Memory: " + freeMem + " KB / " + totalMem + " KB\n" +
            "Platform: " + System.getProperty("microedition.platform") + "\n\n" +
            "Gelaxiz Labs (c) 2026"));
        f.addCommand(cmdBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    // ------------------------------------------------------------------------
    // MULTIPART UPLOAD IMPLEMENTATION
    // ------------------------------------------------------------------------
    private void startUploadThread() {
        if (selectedFilePath == null) {
            showAlert("No File Selected", "Please choose a photo or file first.", AlertType.WARNING, mainForm);
            return;
        }

        final Form progressForm = new Form("Uploading File");
        final StringItem statusText = new StringItem("Status:", "Preparing upload...");
        final Gauge gauge = new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING);
        progressForm.append(statusText);
        progressForm.append(gauge);
        display.setCurrent(progressForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    statusText.setText("Connecting to server...");
                    String resultUrl = executeUpload(statusText);
                    if (resultUrl != null) {
                        showSuccessScreen(resultUrl);
                    } else {
                        showAlert("Upload Failed", "Server did not return a valid download link.", AlertType.ERROR, mainForm);
                    }
                } catch (Exception e) {
                    showAlert("Upload Error", (e.getMessage() != null) ? e.getMessage() : e.toString(), AlertType.ERROR, mainForm);
                }
            }
        });
        t.start();
    }

    private String executeUpload(StringItem statusText) throws Exception {
        String base = serverUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String uploadTarget = base + "/api/mobile/upload";

        try {
            return performUploadRequest(uploadTarget, statusText);
        } catch (Exception ex) {
            // If https failed on SSL handshake or certificate error, auto-retry with http://
            if (uploadTarget.startsWith("https://")) {
                statusText.setText("Retrying over HTTP fallback...");
                String fallbackTarget = "http://" + uploadTarget.substring(8);
                return performUploadRequest(fallbackTarget, statusText);
            } else {
                throw ex;
            }
        }
    }

    private String performUploadRequest(String targetUrl, StringItem statusText) throws Exception {
        String[] expiryVals = {"1h", "24h", "7d", "30d", "never"};
        String expiry = expiryVals[expiryIndex];

        String[] burnVals = {"0", "1", "5", "10"};
        String burn = burnVals[burnIndex];

        String boundary = "----NullhostBoundary" + System.currentTimeMillis();
        String fname = selectedFilePath.substring(selectedFilePath.lastIndexOf('/') + 1);

        long nowSec = System.currentTimeMillis() / 1000L;
        String nonce = Long.toString(System.currentTimeMillis(), 16);
        String tokenPayload = CLIENT_ID + ":" + nowSec + ":" + nonce;
        String tokenSig = CryptoHelper.hmacSha256Hex(MOBILE_SECRET, tokenPayload);
        String appToken = tokenPayload + ":" + tokenSig;

        HttpConnection conn = (HttpConnection) Connector.open(targetUrl, Connector.READ_WRITE);
        conn.setRequestMethod(HttpConnection.POST);
        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("X-Nullhost-App-Token", appToken);
        if (accountId.length() > 0) {
            conn.setRequestProperty("X-Nullhost-Account", accountId);
        }

        statusText.setText("Sending file data...");
        OutputStream os = conn.openOutputStream();

        // Form Fields
        writeField(os, boundary, "format", "json");
        writeField(os, boundary, "expiry", expiry);
        if (!burn.equals("0")) {
            writeField(os, boundary, "burn_views", burn);
        }
        if (customPassword.length() > 0) {
            writeField(os, boundary, "password", customPassword);
        }
        if (customSlug.length() > 0) {
            writeField(os, boundary, "custom_id", customSlug);
        }
        if (accountId.length() > 0) {
            writeField(os, boundary, "account_id", accountId);
        }

        // File Header
        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\n").getBytes("UTF-8"));
        os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));

        // File Payload
        FileConnection fc = (FileConnection) Connector.open(selectedFilePath, Connector.READ);
        InputStream fis = fc.openInputStream();
        byte[] fbuf = new byte[2048];
        int flen;
        long sent = 0;
        while ((flen = fis.read(fbuf)) != -1) {
            os.write(fbuf, 0, flen);
            sent += flen;
            if (sent % 16384 == 0) {
                statusText.setText("Sending: " + formatSize(sent));
            }
        }
        fis.close();
        fc.close();

        // End Boundary
        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();

        statusText.setText("Waiting for server response...");
        int respCode = conn.getResponseCode();

        // Read Response Body
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

    private void writeField(OutputStream os, String boundary, String name, String val) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        os.write((val + "\r\n").getBytes("UTF-8"));
    }

    private void showSuccessScreen(final String resultUrl) {
        final Form successForm = new Form("Upload Successful!");

        successForm.append(new StringItem("Short Link:", resultUrl));
        String rawUrl = resultUrl;
        int vIdx = resultUrl.indexOf("/v/");
        if (vIdx != -1) {
            rawUrl = resultUrl.substring(0, vIdx) + "/raw/" + resultUrl.substring(vIdx + 3);
        }
        successForm.append(new StringItem("Direct Link:", rawUrl));

        final Command cmdOpenBrowser = new Command("Open in Browser", Command.OK, 1);
        final Command cmdNewUpload = new Command("Upload Another", Command.SCREEN, 2);
        final Command cmdExitApp = new Command("Exit", Command.EXIT, 3);

        successForm.addCommand(cmdOpenBrowser);
        successForm.addCommand(cmdNewUpload);
        successForm.addCommand(cmdExitApp);

        successForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdOpenBrowser) {
                    try {
                        platformRequest(resultUrl);
                    } catch (Exception ex) {
                        showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, successForm);
                    }
                } else if (c == cmdNewUpload) {
                    selectedFilePath = null;
                    selectedFileSize = 0;
                    updateFileStatus();
                    display.setCurrent(mainForm);
                } else if (c == cmdExitApp) {
                    destroyApp(true);
                    notifyDestroyed();
                }
            }
        });

        display.setCurrent(successForm);
    }

    private String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = 0;
        while ((idx = json.indexOf(key, idx)) != -1) {
            if (idx == 0 || json.charAt(idx - 1) == '{' || json.charAt(idx - 1) == ',' || json.charAt(idx - 1) == ' ') {
                int colon = json.indexOf(':', idx + key.length());
                if (colon != -1) {
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
            idx += key.length();
        }
        return null;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 KB";
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        long mb = bytes / (1024 * 1024);
        long rem = (bytes % (1024 * 1024)) / 104857;
        return mb + "." + rem + " MB";
    }

    private void showAlert(String title, String msg, AlertType type, Displayable next) {
        Alert alert = new Alert(title, msg, null, type);
        alert.setTimeout(Alert.FOREVER);
        display.setCurrent(alert, next);
    }
}
