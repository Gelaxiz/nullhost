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
 * Complete mobile client with direct photo picker, in-app public file browser,
 * upload history, detailed result cards, and live QR code generation.
 * Pure Java 1.4 (Major version 48) - 100% Dependency-Free.
 * Copyright (c) 2026 Gelaxiz. All rights reserved.
 */
public class NullhostMIDlet extends MIDlet implements CommandListener, ItemCommandListener {

    private static final String APP_VERSION = "1.3.0";
    private static final String CLIENT_ID = "j2me-universal";
    private static final String MOBILE_SECRET = "nullhost_secret_mobile_app_key_2026";
    private static final String DEFAULT_SERVER = "http://nullhost.gelaxiz.xyz";

    private Display display;

    // Server and Account Configuration
    private String serverUrl = DEFAULT_SERVER;
    private String accountId = "";

    // Upload Parameters
    private int expiryIndex = 1; // 24 Hours default
    private int burnIndex = 0;   // Disabled default
    private String customPassword = "";
    private String customSlug = "";

    private static final String[] EXPIRY_LABELS = {"1 Hour", "24 Hours (Default)", "7 Days", "30 Days", "Never Expire"};
    private static final String[] EXPIRY_VALS = {"1h", "24h", "7d", "30d", "never"};

    private static final String[] BURN_LABELS = {"Disabled (Normal)", "1 View (Burn)", "5 Views", "10 Views"};
    private static final String[] BURN_VALS = {"0", "1", "5", "10"};

    // Current Selection
    private String selectedFilePath = null;
    private long selectedFileSize = 0;

    // User Upload History (Session Records)
    private Vector myUploads = new Vector();

    // UI - Main Form
    private Form mainForm;
    private StringItem fileStatusItem;
    private StringItem btnSelectPhoto;
    private StringItem btnBrowseFiles;
    private StringItem btnUploadNow;
    private StringItem btnPublicFiles;
    private StringItem btnMyUploads;
    private StringItem settingsSummaryItem;
    private StringItem btnUploadSettings;
    private StringItem btnServerSettings;

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
    private Command cmdPublicFiles;
    private Command cmdMyUploads;
    private Command cmdOptions;
    private Command cmdServerSettings;
    private Command cmdAbout;
    private Command cmdExit;
    private Command cmdBack;
    private Command cmdSelect;
    private Command cmdSaveOptions;
    private Command cmdSaveServer;
    private Command cmdItemAction;

    // File Browser State
    private List browserList;
    private String currentDirectoryUrl = null;
    private Vector currentEntries = new Vector();

    // Photo Gallery State
    private List galleryList;
    private Vector galleryEntries = new Vector();

    // Public Files State
    private List publicList;
    private Vector publicItems = new Vector();

    public static class UploadRecord {
        public String id = "";
        public String filename = "";
        public long size = 0;
        public String url = "";
        public String rawUrl = "";
        public String qrUrl = "";
        public String deleteUrl = "";
        public String expiry = "24 Hours";
        public String burn = "Disabled";
        public Image qrImage = null;
    }

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
        cmdUpload = new Command("Upload File Now", Command.OK, 1);
        cmdPhoto = new Command("Choose Photo", Command.SCREEN, 2);
        cmdBrowse = new Command("Browse Storage", Command.SCREEN, 3);
        cmdPublicFiles = new Command("Public Files", Command.SCREEN, 4);
        cmdMyUploads = new Command("My Uploads", Command.SCREEN, 5);
        cmdOptions = new Command("Upload Settings", Command.SCREEN, 6);
        cmdServerSettings = new Command("Server Settings", Command.SCREEN, 7);
        cmdAbout = new Command("About", Command.SCREEN, 8);
        cmdExit = new Command("Exit", Command.EXIT, 9);

        cmdBack = new Command("Back", Command.BACK, 1);
        cmdSelect = new Command("Select", Command.OK, 1);
        cmdSaveOptions = new Command("Save", Command.OK, 1);
        cmdSaveServer = new Command("Save", Command.OK, 1);
        cmdItemAction = new Command("Go", Command.ITEM, 1);
    }

    private void initMainForm() {
        mainForm = new Form("Nullhost Mobile");

        // 1. Upload Section
        fileStatusItem = new StringItem("Selected File:", "(None - choose below)");
        mainForm.append(fileStatusItem);

        btnSelectPhoto = new StringItem(null, ">> Choose Photo (Gallery)", Item.HYPERLINK);
        btnSelectPhoto.setDefaultCommand(cmdItemAction);
        btnSelectPhoto.setItemCommandListener(this);
        mainForm.append(btnSelectPhoto);

        btnBrowseFiles = new StringItem(null, ">> Browse All Storage", Item.HYPERLINK);
        btnBrowseFiles.setDefaultCommand(cmdItemAction);
        btnBrowseFiles.setItemCommandListener(this);
        mainForm.append(btnBrowseFiles);

        btnUploadNow = new StringItem(null, "[ UPLOAD FILE NOW ]", Item.BUTTON);
        btnUploadNow.setDefaultCommand(cmdItemAction);
        btnUploadNow.setItemCommandListener(this);
        mainForm.append(btnUploadNow);

        // Separator
        mainForm.append(new StringItem("", "--------------------------------"));

        // 2. Browse & Upload History Section
        btnPublicFiles = new StringItem(null, ">> Browse Public Files", Item.HYPERLINK);
        btnPublicFiles.setDefaultCommand(cmdItemAction);
        btnPublicFiles.setItemCommandListener(this);
        mainForm.append(btnPublicFiles);

        btnMyUploads = new StringItem(null, ">> My Upload History (0)", Item.HYPERLINK);
        btnMyUploads.setDefaultCommand(cmdItemAction);
        btnMyUploads.setItemCommandListener(this);
        mainForm.append(btnMyUploads);

        // Separator
        mainForm.append(new StringItem("", "--------------------------------"));

        // 3. Settings & Stats
        settingsSummaryItem = new StringItem("Settings:", "Expiry: 24h · Burn: Off");
        mainForm.append(settingsSummaryItem);

        btnUploadSettings = new StringItem(null, ">> Change Upload Settings", Item.HYPERLINK);
        btnUploadSettings.setDefaultCommand(cmdItemAction);
        btnUploadSettings.setItemCommandListener(this);
        mainForm.append(btnUploadSettings);

        btnServerSettings = new StringItem(null, ">> Change Server URL", Item.HYPERLINK);
        btnServerSettings.setDefaultCommand(cmdItemAction);
        btnServerSettings.setItemCommandListener(this);
        mainForm.append(btnServerSettings);

        // Softkey Commands
        mainForm.addCommand(cmdUpload);
        mainForm.addCommand(cmdPhoto);
        mainForm.addCommand(cmdBrowse);
        mainForm.addCommand(cmdPublicFiles);
        mainForm.addCommand(cmdMyUploads);
        mainForm.addCommand(cmdOptions);
        mainForm.addCommand(cmdServerSettings);
        mainForm.addCommand(cmdAbout);
        mainForm.addCommand(cmdExit);
        mainForm.setCommandListener(this);
    }

    private void updateMainFormStatus() {
        if (selectedFilePath == null) {
            fileStatusItem.setText("(None - choose below)");
        } else {
            String fname = selectedFilePath.substring(selectedFilePath.lastIndexOf('/') + 1);
            fileStatusItem.setText(fname + " (" + formatSize(selectedFileSize) + ")");
        }
        btnMyUploads.setText(">> My Upload History (" + myUploads.size() + ")");
        settingsSummaryItem.setText("Expiry: " + EXPIRY_LABELS[expiryIndex] + " · Burn: " + BURN_LABELS[burnIndex]);
    }

    public void commandAction(Command c, Item item) {
        if (item == btnSelectPhoto) {
            openPhotoGallery();
        } else if (item == btnBrowseFiles) {
            openFileBrowser(null);
        } else if (item == btnUploadNow) {
            startUploadThread();
        } else if (item == btnPublicFiles) {
            startFetchPublicFiles();
        } else if (item == btnMyUploads) {
            openMyUploads();
        } else if (item == btnUploadSettings) {
            openOptionsForm();
        } else if (item == btnServerSettings) {
            openServerForm();
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
        } else if (c == cmdPublicFiles) {
            startFetchPublicFiles();
        } else if (c == cmdMyUploads) {
            openMyUploads();
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
        } else if ((c == cmdSelect || c == List.SELECT_COMMAND) && d == browserList) {
            handleBrowserSelect();
        } else if ((c == cmdSelect || c == List.SELECT_COMMAND) && d == galleryList) {
            handleGallerySelect();
        } else if ((c == cmdSelect || c == List.SELECT_COMMAND) && d == publicList) {
            handlePublicSelect();
        } else if (c == cmdSaveOptions) {
            saveOptions();
        } else if (c == cmdSaveServer) {
            saveServer();
        }
    }

    // ------------------------------------------------------------------------
    // PHOTO GALLERY (DIRECT CAMERA DIRECTORY SCANNER)
    // ------------------------------------------------------------------------
    private void openPhotoGallery() {
        galleryEntries.removeAllElements();
        galleryList = new List("Choose Photo", List.IMPLICIT);
        galleryList.addCommand(cmdSelect);
        galleryList.addCommand(cmdBack);
        galleryList.setCommandListener(this);

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
            if (galleryEntries.size() >= 80) break;
        }

        if (galleryEntries.isEmpty()) {
            try {
                Enumeration roots = FileSystemRegistry.listRoots();
                while (roots.hasMoreElements()) {
                    String root = (String) roots.nextElement();
                    scanDirectoryForPhotos("file:///" + root, 0);
                    if (galleryEntries.size() >= 80) break;
                }
            } catch (Exception ex) {}
        }

        if (galleryEntries.isEmpty()) {
            showAlert("No Photos Found", "No images (.jpg, .png, .gif) found in camera storage folders.", AlertType.INFO, mainForm);
            return;
        }

        display.setCurrent(galleryList);
    }

    private void scanDirectoryForPhotos(String dirUrl, int depth) {
        if (depth > 2 || galleryEntries.size() >= 80) return;
        try {
            FileConnection fc = (FileConnection) Connector.open(dirUrl, Connector.READ);
            if (fc.isDirectory()) {
                Enumeration list = fc.list();
                Vector foundHere = new Vector();
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
                            foundHere.addElement(full);
                        }
                    }
                }
                fc.close();

                // Insert into gallery list (reverse order so recent photos show first)
                for (int i = foundHere.size() - 1; i >= 0; i--) {
                    String pUrl = (String) foundHere.elementAt(i);
                    galleryEntries.addElement(pUrl);
                    String fname = pUrl.substring(pUrl.lastIndexOf('/') + 1);
                    long size = 0;
                    try {
                        FileConnection ifc = (FileConnection) Connector.open(pUrl, Connector.READ);
                        size = ifc.fileSize();
                        ifc.close();
                    } catch (Exception e) {}
                    galleryList.append(fname + " (" + formatSize(size) + ")", null);
                    if (galleryEntries.size() >= 80) break;
                }
            } else {
                fc.close();
            }
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

        updateMainFormStatus();
        display.setCurrent(mainForm);
    }

    // ------------------------------------------------------------------------
    // FILE BROWSER (JSR-75 STORAGE NAVIGATION)
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
                    browserList.append(formatDriveLabel(root), null);
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
            updateMainFormStatus();
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
    // BROWSE PUBLIC FILES (EXISTING FILES ON SERVER)
    // ------------------------------------------------------------------------
    private void startFetchPublicFiles() {
        final Form loadingForm = new Form("Public Files");
        loadingForm.append(new StringItem("Status:", "Connecting to server..."));
        loadingForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(loadingForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String url = base + "/api/mobile/public";

                    HttpConnection conn = (HttpConnection) Connector.open(url, Connector.READ);
                    conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                    int code = conn.getResponseCode();

                    if (code != 200) {
                        conn.close();
                        showAlert("Public Files", "Server error (HTTP " + code + ")", AlertType.ERROR, mainForm);
                        return;
                    }

                    InputStream is = conn.openInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[512];
                    int len;
                    while ((len = is.read(buf)) != -1) {
                        baos.write(buf, 0, len);
                    }
                    is.close();
                    conn.close();

                    String json = new String(baos.toByteArray(), "UTF-8");
                    publicItems = parseItemsArray(json);

                    if (publicItems.isEmpty()) {
                        showAlert("Public Files", "No active public files currently hosted.", AlertType.INFO, mainForm);
                        return;
                    }

                    publicList = new List("Public Files (" + publicItems.size() + ")", List.IMPLICIT);
                    publicList.addCommand(cmdSelect);
                    publicList.addCommand(cmdBack);
                    publicList.setCommandListener(NullhostMIDlet.this);

                    for (int i = 0; i < publicItems.size(); i++) {
                        UploadRecord rec = (UploadRecord) publicItems.elementAt(i);
                        publicList.append(rec.filename + " (" + formatSize(rec.size) + ")", null);
                    }

                    display.setCurrent(publicList);
                } catch (Exception e) {
                    showAlert("Public Files", "Could not fetch files: " + e.getMessage(), AlertType.ERROR, mainForm);
                }
            }
        });
        t.start();
    }

    private void handlePublicSelect() {
        int idx = publicList.getSelectedIndex();
        if (idx < 0 || idx >= publicItems.size()) return;
        UploadRecord rec = (UploadRecord) publicItems.elementAt(idx);
        showItemDetails(rec, publicList);
    }

    // ------------------------------------------------------------------------
    // MY UPLOAD HISTORY
    // ------------------------------------------------------------------------
    private void openMyUploads() {
        if (myUploads.isEmpty()) {
            showAlert("Upload History", "No uploads performed during this session yet.", AlertType.INFO, mainForm);
            return;
        }

        final List histList = new List("My Uploads (" + myUploads.size() + ")", List.IMPLICIT);
        final Command cmdHistSelect = new Command("View", Command.OK, 1);
        final Command cmdHistBack = new Command("Back", Command.BACK, 2);
        histList.addCommand(cmdHistSelect);
        histList.addCommand(cmdHistBack);

        for (int i = 0; i < myUploads.size(); i++) {
            UploadRecord rec = (UploadRecord) myUploads.elementAt(i);
            histList.append(rec.filename + " (" + formatSize(rec.size) + ")", null);
        }

        histList.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdHistSelect || c == List.SELECT_COMMAND) {
                    int idx = histList.getSelectedIndex();
                    if (idx >= 0 && idx < myUploads.size()) {
                        UploadRecord rec = (UploadRecord) myUploads.elementAt(idx);
                        showItemDetails(rec, histList);
                    }
                } else if (c == cmdHistBack) {
                    display.setCurrent(mainForm);
                }
            }
        });

        display.setCurrent(histList);
    }

    // ------------------------------------------------------------------------
    // ITEM DETAILS SCREEN (WITH LIVE QR CODE)
    // ------------------------------------------------------------------------
    private void showItemDetails(final UploadRecord rec, final Displayable backTarget) {
        final Form f = new Form("File Details");

        if (rec.qrImage != null) {
            f.append(new ImageItem("QR Code:", rec.qrImage, Item.LAYOUT_CENTER, "Scan to Download"));
        }

        f.append(new StringItem("File Name:", rec.filename));
        if (rec.id != null && rec.id.length() > 0) {
            f.append(new StringItem("Item Code:", rec.id));
        }
        f.append(new StringItem("File Size:", formatSize(rec.size)));
        if (rec.expiry != null && rec.expiry.length() > 0) {
            f.append(new StringItem("Retention:", rec.expiry));
        }
        if (rec.burn != null && rec.burn.length() > 0) {
            f.append(new StringItem("Burn Limit:", rec.burn));
        }
        if (rec.url != null && rec.url.length() > 0) {
            f.append(new StringItem("Short URL:", rec.url));
        }
        if (rec.rawUrl != null && rec.rawUrl.length() > 0) {
            f.append(new StringItem("Raw URL:", rec.rawUrl));
        }
        if (rec.deleteUrl != null && rec.deleteUrl.length() > 0) {
            f.append(new StringItem("Delete URL:", rec.deleteUrl));
        }

        final Command cmdOpen = new Command("Open in Browser", Command.OK, 1);
        final Command cmdDetailsBack = new Command("Back", Command.BACK, 2);

        f.addCommand(cmdOpen);
        f.addCommand(cmdDetailsBack);
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdOpen) {
                    if (rec.url != null && rec.url.length() > 0) {
                        try {
                            platformRequest(rec.url);
                        } catch (Exception ex) {
                            showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, f);
                        }
                    }
                } else if (c == cmdDetailsBack) {
                    display.setCurrent(backTarget);
                }
            }
        });

        // If QR code is not yet loaded, load it in background and refresh
        if (rec.qrImage == null && rec.qrUrl != null && rec.qrUrl.length() > 0) {
            Thread qrThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        HttpConnection conn = (HttpConnection) Connector.open(rec.qrUrl, Connector.READ);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        if (conn.getResponseCode() == 200) {
                            InputStream is = conn.openInputStream();
                            rec.qrImage = Image.createImage(is);
                            is.close();
                            conn.close();
                            showItemDetails(rec, backTarget);
                        } else {
                            conn.close();
                        }
                    } catch (Exception ex) {}
                }
            });
            qrThread.start();
        }

        display.setCurrent(f);
    }

    // ------------------------------------------------------------------------
    // MULTIPART UPLOAD & LIVE QR GENERATION
    // ------------------------------------------------------------------------
    private void startUploadThread() {
        if (selectedFilePath == null) {
            showAlert("No File Selected", "Please choose a photo or file first.", AlertType.WARNING, mainForm);
            return;
        }

        final Form progressForm = new Form("Uploading File");
        final StringItem statusText = new StringItem("Status:", "Connecting to server...");
        final Gauge gauge = new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING);
        progressForm.append(statusText);
        progressForm.append(gauge);
        display.setCurrent(progressForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    UploadRecord record = executeUpload(statusText);
                    if (record != null) {
                        showSuccessScreen(record);
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

    private UploadRecord executeUpload(StringItem statusText) throws Exception {
        String base = serverUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String uploadTarget = base + "/api/mobile/upload";

        UploadRecord record = null;
        try {
            record = performUploadRequest(uploadTarget, statusText);
        } catch (Exception ex) {
            if (uploadTarget.startsWith("https://")) {
                statusText.setText("Retrying over HTTP fallback...");
                String fallbackTarget = "http://" + uploadTarget.substring(8);
                record = performUploadRequest(fallbackTarget, statusText);
            } else {
                throw ex;
            }
        }

        // Fetch Live QR Code image
        if (record != null && record.qrUrl != null && record.qrUrl.length() > 0) {
            statusText.setText("Generating QR code image...");
            try {
                HttpConnection qrConn = (HttpConnection) Connector.open(record.qrUrl, Connector.READ);
                qrConn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                if (qrConn.getResponseCode() == 200) {
                    InputStream qris = qrConn.openInputStream();
                    record.qrImage = Image.createImage(qris);
                    qris.close();
                }
                qrConn.close();
            } catch (Exception ex) {}
        }

        return record;
    }

    private UploadRecord performUploadRequest(String targetUrl, StringItem statusText) throws Exception {
        String expiry = EXPIRY_VALS[expiryIndex];
        String burn = BURN_VALS[burnIndex];

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

        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\n").getBytes("UTF-8"));
        os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));

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

        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();

        statusText.setText("Waiting for server response...");
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
            UploadRecord record = new UploadRecord();
            record.id = extractJsonField(respStr, "id");
            record.filename = extractJsonField(respStr, "filename");
            if (record.filename == null) record.filename = fname;

            String sizeStr = extractJsonField(respStr, "size");
            if (sizeStr != null) {
                try { record.size = Long.parseLong(sizeStr); } catch (Exception e) {}
            } else {
                record.size = selectedFileSize;
            }

            record.url = extractJsonField(respStr, "url");
            record.rawUrl = extractJsonField(respStr, "raw_url");
            record.qrUrl = extractJsonField(respStr, "qr_url");
            record.deleteUrl = extractJsonField(respStr, "delete_url");
            record.expiry = EXPIRY_LABELS[expiryIndex];
            record.burn = BURN_LABELS[burnIndex];

            if (record.url == null) record.url = targetUrl;
            if (record.rawUrl == null) record.rawUrl = record.url;
            if (record.qrUrl == null && record.url != null) record.qrUrl = record.url + "/qr.png";

            myUploads.insertElementAt(record, 0);
            return record;
        }

        return null;
    }

    private void writeField(OutputStream os, String boundary, String name, String val) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        os.write((val + "\r\n").getBytes("UTF-8"));
    }

    // ------------------------------------------------------------------------
    // UPLOAD SUCCESS SCREEN (DETAILED INFO & PROMINENT QR CODE)
    // ------------------------------------------------------------------------
    private void showSuccessScreen(final UploadRecord record) {
        final Form successForm = new Form("Upload Successful!");

        if (record.qrImage != null) {
            successForm.append(new ImageItem("Scan QR Code:", record.qrImage, Item.LAYOUT_CENTER, "Scan to Download"));
        }

        successForm.append(new StringItem("Item Code:", record.id));
        successForm.append(new StringItem("File Name:", record.filename));
        successForm.append(new StringItem("File Size:", formatSize(record.size)));
        successForm.append(new StringItem("Retention:", record.expiry));
        successForm.append(new StringItem("Burn Limit:", record.burn));
        successForm.append(new StringItem("Short URL:", record.url));
        successForm.append(new StringItem("Direct Raw Link:", record.rawUrl));
        if (record.deleteUrl != null && record.deleteUrl.length() > 0) {
            successForm.append(new StringItem("Delete Link:", record.deleteUrl));
        }

        final Command cmdOpen = new Command("Open in Browser", Command.OK, 1);
        final Command cmdNew = new Command("Upload Another", Command.SCREEN, 2);
        final Command cmdHome = new Command("Back to Home", Command.SCREEN, 3);
        final Command cmdExitApp = new Command("Exit", Command.EXIT, 4);

        successForm.addCommand(cmdOpen);
        successForm.addCommand(cmdNew);
        successForm.addCommand(cmdHome);
        successForm.addCommand(cmdExitApp);

        successForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdOpen) {
                    try {
                        platformRequest(record.url);
                    } catch (Exception ex) {
                        showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, successForm);
                    }
                } else if (c == cmdNew) {
                    selectedFilePath = null;
                    selectedFileSize = 0;
                    updateMainFormStatus();
                    display.setCurrent(mainForm);
                } else if (c == cmdHome) {
                    updateMainFormStatus();
                    display.setCurrent(mainForm);
                } else if (c == cmdExitApp) {
                    destroyApp(true);
                    notifyDestroyed();
                }
            }
        });

        display.setCurrent(successForm);
    }

    // ------------------------------------------------------------------------
    // SETTINGS FORMS
    // ------------------------------------------------------------------------
    private void openOptionsForm() {
        optionsForm = new Form("Upload Settings");

        optExpiry = new ChoiceGroup("Retention Time:", Choice.EXCLUSIVE, EXPIRY_LABELS, null);
        optExpiry.setSelectedIndex(expiryIndex, true);
        optionsForm.append(optExpiry);

        optBurn = new ChoiceGroup("Burn After Reading:", Choice.EXCLUSIVE, BURN_LABELS, null);
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

        updateMainFormStatus();
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
        updateMainFormStatus();
        display.setCurrent(mainForm);
    }

    private void showAbout() {
        long totalMem = Runtime.getRuntime().totalMemory() / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024;

        Form f = new Form("About Nullhost");
        f.append(new StringItem("Nullhost Universal J2ME", "Version " + APP_VERSION + "\n\n" +
            "Fast, anonymous file and photo hosting for Symbian and vintage phones.\n\n" +
            "Features:\n" +
            "- Instant camera photo picker\n" +
            "- JSR-75 filesystem navigation\n" +
            "- Live QR code generation\n" +
            "- Browse public files & upload history\n" +
            "- Direct HMAC mobile uploads\n\n" +
            "Server: " + serverUrl + "\n" +
            "Free Memory: " + freeMem + " KB / " + totalMem + " KB\n" +
            "Platform: " + System.getProperty("microedition.platform") + "\n\n" +
            "Gelaxiz Labs (c) 2026"));
        f.addCommand(cmdBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    // ------------------------------------------------------------------------
    // UTILITY METHODS & JSON PARSER
    // ------------------------------------------------------------------------
    private Vector parseItemsArray(String json) {
        Vector list = new Vector();
        if (json == null) return list;
        int itemsStart = json.indexOf("\"items\":");
        if (itemsStart == -1) itemsStart = json.indexOf("\"items\" :");
        if (itemsStart == -1) return list;

        int bracketStart = json.indexOf('[', itemsStart);
        if (bracketStart == -1) return list;

        int bracketEnd = json.lastIndexOf(']');
        if (bracketEnd == -1 || bracketEnd <= bracketStart) return list;

        int cur = bracketStart;
        while (cur < bracketEnd) {
            int objStart = json.indexOf('{', cur);
            if (objStart == -1 || objStart >= bracketEnd) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd == -1) break;

            String objStr = json.substring(objStart + 1, objEnd);
            UploadRecord rec = new UploadRecord();
            rec.id = extractJsonField(objStr, "id");
            rec.filename = extractJsonField(objStr, "filename");
            String sizeStr = extractJsonField(objStr, "size");
            if (sizeStr != null) {
                try {
                    rec.size = Long.parseLong(sizeStr);
                } catch (Exception ex) {}
            }
            rec.url = extractJsonField(objStr, "url");
            rec.rawUrl = extractJsonField(objStr, "raw_url");
            if (rec.url != null && rec.url.length() > 0) {
                rec.qrUrl = rec.url + "/qr.png";
            }
            if (rec.filename != null && rec.filename.length() > 0) {
                list.addElement(rec);
            }
            cur = objEnd + 1;
        }
        return list;
    }

    private String extractJsonField(String json, String field) {
        if (json == null || field == null) return null;
        String key = "\"" + field + "\"";
        int idx = 0;
        while ((idx = json.indexOf(key, idx)) != -1) {
            if (idx == 0 || json.charAt(idx - 1) == '{' || json.charAt(idx - 1) == ',' || json.charAt(idx - 1) == ' ' || json.charAt(idx - 1) == '\n') {
                int colon = json.indexOf(':', idx + key.length());
                if (colon != -1) {
                    int start = colon + 1;
                    while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\"' || json.charAt(start) == '\r' || json.charAt(start) == '\n')) {
                        start++;
                    }
                    int end = start;
                    while (end < json.length() && json.charAt(end) != '\"' && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != '\r' && json.charAt(end) != '\n') {
                        end++;
                    }
                    if (start <= end) {
                        return json.substring(start, end).trim();
                    }
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
