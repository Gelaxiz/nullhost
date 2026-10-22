package org.gelaxiz.nullhost;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.*;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.rms.RecordStore;

/**
 * Nullhost Universal Java ME (MIDP 2.0 / CLDC 1.1 / JSR-75) Client.
 * Complete mobile client with native touch buttons, photo QR code scanner,
 * storage explorer, in-app code search, server file deletion, custom text viewer,
 * persistent RMS settings storage, and live centered QR code display.
 * Pure Java 1.4 (Major version 48) - 100% Dependency-Free.
 * Copyright (c) 2026 Gelaxiz. All rights reserved.
 */
public class NullhostMIDlet extends MIDlet implements CommandListener, ItemCommandListener {

    private static final String APP_VERSION = "1.8.2";
    private static final String CLIENT_ID = "j2me-universal";
    private static final String MOBILE_SECRET = "nullhost_secret_mobile_app_key_2026";
    private static final String DEFAULT_SERVER = "http://nullhost.gelaxiz.xyz";
    private static final String RMS_CONFIG_NAME = "nullhost_cfg";

    private Display display;

    // Server and Account Configuration
    private String serverUrl = DEFAULT_SERVER;
    private String accountId = "";

    // Upload Parameters
    private int expiryIndex = 1; // 24 Hours default
    private int burnIndex = 0;   // Disabled default
    private String customPassword = "";
    private String customSlug = "";

    private static final String[] EXPIRY_LABELS = {"1 Hour", "24 Hours", "7 Days", "30 Days", "Never Expire"};
    private static final String[] EXPIRY_VALS = {"1h", "24h", "7d", "30d", "never"};

    private static final String[] BURN_LABELS = {"Disabled", "1 View", "5 Views", "10 Views"};
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
    private StringItem btnFindByCode;
    private StringItem btnPublicFiles;
    private StringItem btnMyUploads;
    private StringItem settingsSummaryItem;
    private StringItem btnSettings;

    // QR Scanner from Photo State
    private Command cmdScanGalleryQR = null;
    private boolean galleryForQR = false;
    private boolean browserForQR = false;

    // Search by Code Form
    private Form findForm;
    private TextField findCodeField;
    private StringItem btnScanPhotoFind;
    private Command cmdDoSearch;

    // Consolidated Settings Form
    private Form settingsForm;
    private TextField optServerUrl;
    private ChoiceGroup optExpiry;
    private ChoiceGroup optBurn;
    private TextField optPassword;
    private TextField optSlug;
    private TextField optAccount;

    // Commands
    private Command cmdUpload;
    private Command cmdPhoto;
    private Command cmdBrowse;
    private Command cmdFindByCodeMenu;
    private Command cmdPublicFiles;
    private Command cmdMyUploads;
    private Command cmdSettings;
    private Command cmdTerms;
    private Command cmdPrivacy;
    private Command cmdAbout;
    private Command cmdExit;
    private Command cmdExitSoftkey;
    private Command cmdBack;
    private Command cmdSelect;
    private Command cmdSaveSettings;
    private Command cmdItemAction;

    // File Browser State
    private List browserList;
    private String currentDirectoryUrl = null;
    private Vector currentEntries = new Vector();

    // Photo Gallery State
    private List galleryList;
    private Vector galleryEntries = new Vector();

    static class PhotoItem {
        String url;
        String name;
        long time;
        PhotoItem(String u, String n, long t) {
            this.url = u;
            this.name = n;
            this.time = t;
        }
    }

    // Public Files State
    private List publicList;
    private Vector publicItems = new Vector();

    public static class UploadRecord {
        public String id = "";
        public String filename = "";
        public long size = 0;
        public String type = "file";
        public String url = "";
        public String rawUrl = "";
        public String downloadUrl = "";
        public String qrUrl = "";
        public String deleteUrl = "";
        public String expiry = "24 Hours";
        public long expiresAt = 0;
        public String burn = "Disabled";
        public String content = null;
        public Image qrImage = null;
        public boolean hasPassword = false;
        public String password = "";
    }

    public void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            loadPersistentSettings();
            initCommands();
            initMainForm();
        }
        display.setCurrent(mainForm);
    }

    public void pauseApp() {
    }

    public void destroyApp(boolean unconditional) {
        savePersistentSettings();
    }

    private void initCommands() {
        cmdUpload = new Command("Upload File Now", Command.OK, 1);
        cmdPhoto = new Command("Choose Photo", Command.SCREEN, 2);
        cmdBrowse = new Command("Browse Storage", Command.SCREEN, 3);
        cmdFindByCodeMenu = new Command("Find by Code", Command.SCREEN, 4);
        cmdPublicFiles = new Command("Public Files", Command.SCREEN, 5);
        cmdMyUploads = new Command("Upload History", Command.SCREEN, 6);
        cmdSettings = new Command("Settings", Command.SCREEN, 7);
        cmdTerms = new Command("Terms of Service", Command.SCREEN, 8);
        cmdPrivacy = new Command("Privacy Policy", Command.SCREEN, 9);
        cmdAbout = new Command("About", Command.SCREEN, 10);
        cmdExit = new Command("Exit", Command.SCREEN, 11);
        cmdExitSoftkey = new Command("Exit", Command.EXIT, 12);

        cmdBack = new Command("Back", Command.BACK, 1);
        cmdSelect = new Command("Select", Command.OK, 1);
        cmdSaveSettings = new Command("Save", Command.OK, 1);
        cmdDoSearch = new Command("Search", Command.OK, 1);
        cmdItemAction = new Command("Go", Command.ITEM, 1);
        cmdScanGalleryQR = new Command("Scan QR from Photo", Command.SCREEN, 2);
    }

    private void initMainForm() {
        mainForm = new Form("Nullhost Mobile");

        // 1. Upload Section
        fileStatusItem = new StringItem("Selected File:", "No file selected");
        fileStatusItem.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        mainForm.append(fileStatusItem);

        btnSelectPhoto = new StringItem(null, "Choose Photo", Item.BUTTON);
        btnSelectPhoto.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnSelectPhoto.setDefaultCommand(cmdItemAction);
        btnSelectPhoto.setItemCommandListener(this);
        mainForm.append(btnSelectPhoto);

        btnBrowseFiles = new StringItem(null, "Browse Files", Item.BUTTON);
        btnBrowseFiles.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnBrowseFiles.setDefaultCommand(cmdItemAction);
        btnBrowseFiles.setItemCommandListener(this);
        mainForm.append(btnBrowseFiles);

        btnUploadNow = new StringItem(null, "Upload File Now", Item.BUTTON);
        btnUploadNow.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnUploadNow.setDefaultCommand(cmdItemAction);
        btnUploadNow.setItemCommandListener(this);
        mainForm.append(btnUploadNow);

        mainForm.append(new Spacer(10, 8));

        // 2. Discover & Search Section
        btnFindByCode = new StringItem(null, "Find by Code", Item.BUTTON);
        btnFindByCode.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnFindByCode.setDefaultCommand(cmdItemAction);
        btnFindByCode.setItemCommandListener(this);
        mainForm.append(btnFindByCode);

        btnPublicFiles = new StringItem(null, "Browse Public Files", Item.BUTTON);
        btnPublicFiles.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnPublicFiles.setDefaultCommand(cmdItemAction);
        btnPublicFiles.setItemCommandListener(this);
        mainForm.append(btnPublicFiles);

        btnMyUploads = new StringItem(null, "Upload History", Item.BUTTON);
        btnMyUploads.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnMyUploads.setDefaultCommand(cmdItemAction);
        btnMyUploads.setItemCommandListener(this);
        mainForm.append(btnMyUploads);

        mainForm.append(new Spacer(10, 8));

        // 3. Consolidated Settings Section
        settingsSummaryItem = new StringItem("Settings:", "Expiry " + EXPIRY_LABELS[expiryIndex] + ", Burn " + BURN_LABELS[burnIndex]);
        settingsSummaryItem.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        mainForm.append(settingsSummaryItem);

        btnSettings = new StringItem(null, "Settings", Item.BUTTON);
        btnSettings.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnSettings.setDefaultCommand(cmdItemAction);
        btnSettings.setItemCommandListener(this);
        mainForm.append(btnSettings);

        // Softkey Commands (Only non-redundant items in options menu; main actions are on-screen buttons)
        mainForm.addCommand(cmdTerms);
        mainForm.addCommand(cmdPrivacy);
        mainForm.addCommand(cmdAbout);
        mainForm.addCommand(cmdExit);
        mainForm.addCommand(cmdExitSoftkey);
        mainForm.setCommandListener(this);
    }

    private void updateMainFormStatus() {
        if (selectedFilePath == null) {
            fileStatusItem.setText("No file selected");
        } else {
            String fname = selectedFilePath.substring(selectedFilePath.lastIndexOf('/') + 1);
            fileStatusItem.setText(fname + " - " + formatSize(selectedFileSize));
        }
        if (myUploads.isEmpty()) {
            btnMyUploads.setText("Upload History");
        } else {
            btnMyUploads.setText("Upload History: " + myUploads.size() + " items");
        }
        settingsSummaryItem.setText("Expiry " + EXPIRY_LABELS[expiryIndex] + ", Burn " + BURN_LABELS[burnIndex]);
    }

    public void commandAction(Command c, Item item) {
        if (item == btnSelectPhoto) {
            openPhotoGallery();
        } else if (item == btnBrowseFiles) {
            openFileBrowser(null);
        } else if (item == btnUploadNow) {
            startUploadThread();
        } else if (item == btnScanPhotoFind) {
            openPhotoGalleryForQR();
        } else if (item == btnFindByCode) {
            openFindByCodeForm();
        } else if (item == btnPublicFiles) {
            startFetchPublicFiles();
        } else if (item == btnMyUploads) {
            openMyUploads();
        } else if (item == btnSettings) {
            openSettingsForm();
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit || c == cmdExitSoftkey) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == cmdScanGalleryQR) {
            openPhotoGalleryForQR();
        } else if (c == cmdUpload) {
            startUploadThread();
        } else if (c == cmdPhoto) {
            openPhotoGallery();
        } else if (c == cmdBrowse) {
            openFileBrowser(null);
        } else if (c == cmdFindByCodeMenu) {
            openFindByCodeForm();
        } else if (c == cmdPublicFiles) {
            startFetchPublicFiles();
        } else if (c == cmdMyUploads) {
            openMyUploads();
        } else if (c == cmdSettings) {
            openSettingsForm();
        } else if (c == cmdAbout) {
            showAbout();
        } else if (c == cmdTerms) {
            showTermsOfService();
        } else if (c == cmdPrivacy) {
            showPrivacyPolicy();
        } else if (c == cmdBack) {
            if (d == galleryList) {
                if (galleryForQR) {
                    galleryForQR = false;
                    display.setCurrent(findForm != null ? (Displayable) findForm : mainForm);
                } else {
                    display.setCurrent(mainForm);
                }
            } else if (d == browserList) {
                handleBrowserBack();
            } else if (d == settingsForm) {
                saveSettings();
            } else {
                display.setCurrent(mainForm);
            }
        } else if ((c == cmdSelect || c == List.SELECT_COMMAND) && d == browserList) {
            handleBrowserSelect();
        } else if ((c == cmdSelect || c == List.SELECT_COMMAND) && d == galleryList) {
            handleGallerySelect();
        } else if ((c == cmdSelect || c == List.SELECT_COMMAND) && d == publicList) {
            handlePublicSelect();
        } else if (c == cmdSaveSettings) {
            saveSettings();
        } else if (c == cmdDoSearch && d == findForm) {
            executeSearchByCode();
        }
    }

    // ------------------------------------------------------------------------
    // RMS PERSISTENT SETTINGS (SAVE & LOAD)
    // ------------------------------------------------------------------------
    private void savePersistentSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_CONFIG_NAME, true);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeUTF(serverUrl != null ? serverUrl : DEFAULT_SERVER);
            dos.writeUTF(accountId != null ? accountId : "");
            dos.writeInt(expiryIndex);
            dos.writeInt(burnIndex);
            dos.writeUTF(customPassword != null ? customPassword : "");
            dos.writeUTF(customSlug != null ? customSlug : "");
            dos.flush();
            byte[] data = baos.toByteArray();
            if (rs.getNumRecords() == 0) {
                rs.addRecord(data, 0, data.length);
            } else {
                rs.setRecord(1, data, 0, data.length);
            }
        } catch (Exception ex) {
            // Ignore RMS exception
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }
    }

    private void loadPersistentSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_CONFIG_NAME, false);
            if (rs != null && rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                if (data != null && data.length > 0) {
                    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
                    serverUrl = dis.readUTF();
                    accountId = dis.readUTF();
                    expiryIndex = dis.readInt();
                    burnIndex = dis.readInt();
                    customPassword = dis.readUTF();
                    customSlug = dis.readUTF();
                }
            }
        } catch (Exception ex) {
            // RecordStore does not exist yet (first launch)
        } finally {
            if (rs != null) {
                try { rs.closeRecordStore(); } catch (Exception e) {}
            }
        }

        // Sanity checks
        if (serverUrl == null || serverUrl.length() == 0) serverUrl = DEFAULT_SERVER;
        if (accountId == null) accountId = "";
        if (expiryIndex < 0 || expiryIndex >= EXPIRY_LABELS.length) expiryIndex = 1;
        if (burnIndex < 0 || burnIndex >= BURN_LABELS.length) burnIndex = 0;
        if (customPassword == null) customPassword = "";
        if (customSlug == null) customSlug = "";
    }

    // ------------------------------------------------------------------------
    // FIND FILE BY CODE
    // ------------------------------------------------------------------------
    private void openFindByCodeForm() {
        findForm = new Form("Find File by Code");
        findCodeField = new TextField("Enter Item Code or URL:", "", 64, TextField.ANY);
        findForm.append(findCodeField);

        findForm.append(new Spacer(10, 8));

        btnScanPhotoFind = new StringItem(null, "Scan QR from Photo Gallery", Item.BUTTON);
        btnScanPhotoFind.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnScanPhotoFind.setDefaultCommand(cmdItemAction);
        btnScanPhotoFind.setItemCommandListener(this);
        findForm.append(btnScanPhotoFind);

        findForm.addCommand(cmdDoSearch);
        findForm.addCommand(cmdScanGalleryQR);
        findForm.addCommand(cmdBack);
        findForm.setCommandListener(this);
        display.setCurrent(findForm);
    }

    private void executeSearchByCode() {
        String rawInput = findCodeField.getString().trim();
        if (rawInput.length() == 0) {
            showAlert("Input Required", "Please enter an item code or link.", AlertType.WARNING, findForm);
            return;
        }

        // Clean user input: strip any full URL, path prefix, or query params
        String code = rawInput;
        int vIdx = code.indexOf("/v/");
        if (vIdx != -1) {
            code = code.substring(vIdx + 3);
        } else {
            int rawIdx = code.indexOf("/raw/");
            if (rawIdx != -1) {
                code = code.substring(rawIdx + 5);
            }
        }
        int slashIdx = code.lastIndexOf('/');
        if (slashIdx != -1) {
            code = code.substring(slashIdx + 1);
        }
        int qIdx = code.indexOf('?');
        if (qIdx != -1) {
            code = code.substring(0, qIdx);
        }
        code = code.trim();

        if (code.length() == 0) {
            showAlert("Invalid Code", "Please enter a valid item code.", AlertType.WARNING, findForm);
            return;
        }

        lookupItemByCode(code, findForm);
    }

    private void lookupItemByCode(final String cleanCode, final Displayable backTarget) {
        final Form searchingForm = new Form("Searching");
        searchingForm.append(new StringItem("Status:", "Looking up " + cleanCode + " on server..."));
        searchingForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(searchingForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String lookupUrl = base + "/api/mobile/item/" + cleanCode;

                    HttpConnection conn = null;
                    try {
                        conn = (HttpConnection) Connector.open(lookupUrl, Connector.READ);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        int respCode = conn.getResponseCode();
                        if (respCode == 404 || respCode == 410) {
                            conn.close();
                            showAlert("Not Found", "No active item found matching code: " + cleanCode, AlertType.INFO, backTarget);
                            return;
                        }
                        if (respCode != 200) {
                            conn.close();
                            showAlert("Search Error", "Server returned HTTP " + respCode, AlertType.ERROR, backTarget);
                            return;
                        }
                    } catch (Exception ex) {
                        if (lookupUrl.startsWith("https://")) {
                            String httpUrl = "http://" + lookupUrl.substring(8);
                            conn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            int respCode = conn.getResponseCode();
                            if (respCode == 404 || respCode == 410) {
                                conn.close();
                                showAlert("Not Found", "No active item found matching code: " + cleanCode, AlertType.INFO, backTarget);
                                return;
                            }
                            if (respCode != 200) {
                                conn.close();
                                showAlert("Search Error", "Server returned HTTP " + respCode, AlertType.ERROR, backTarget);
                                return;
                            }
                        } else {
                            throw ex;
                        }
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
                    UploadRecord rec = new UploadRecord();
                    rec.id = extractJsonField(json, "id");
                    rec.filename = extractJsonField(json, "filename");
                    rec.type = extractJsonField(json, "type");
                    String sizeStr = extractJsonField(json, "size");
                    if (sizeStr != null) {
                        try { rec.size = Long.parseLong(sizeStr); } catch (Exception ex) {}
                    }
                    String expStr = extractJsonField(json, "expires_at");
                    if (expStr != null) {
                        try { rec.expiresAt = Long.parseLong(expStr); } catch (Exception ex) {}
                    }
                    rec.url = extractJsonField(json, "url");
                    rec.rawUrl = extractJsonField(json, "raw_url");
                    rec.downloadUrl = extractJsonField(json, "download_url");
                    rec.qrUrl = extractJsonField(json, "qr_url");
                    if (rec.qrUrl == null && rec.url != null) rec.qrUrl = rec.url + "/qr.png?size=5";
                    rec.content = extractJsonField(json, "content");
                    rec.expiry = formatRetentionDisplay(null, rec.expiresAt);
                    rec.hasPassword = "true".equals(extractJsonField(json, "has_password"));
                    boolean pwdOk = "true".equals(extractJsonField(json, "password_ok"));

                    if (rec.hasPassword && !pwdOk) {
                        promptFilePassword(rec, cleanCode, backTarget, false);
                    } else {
                        showItemDetails(rec, backTarget, false);
                    }
                } catch (Exception e) {
                    showAlert("Search Error", "Could not connect to server: " + e.getMessage(), AlertType.ERROR, backTarget);
                }
            }
        });
        t.start();
    }

    // ------------------------------------------------------------------------
    // QR CODE SCANNING VIA PHOTO GALLERY
    // ------------------------------------------------------------------------
    private void sendImageToDecodeQR(final byte[] imgBytes, final Displayable returnTarget) {
        final Form processingForm = new Form("Decoding QR");
        processingForm.append(new StringItem("Status:", "Analyzing image for QR code (" + (imgBytes.length / 1024) + " KB)..."));
        processingForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(processingForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String url = base + "/api/mobile/qr-decode";

                    HttpConnection conn = null;
                    try {
                        conn = (HttpConnection) Connector.open(url, Connector.READ_WRITE);
                        conn.setRequestMethod(HttpConnection.POST);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        conn.setRequestProperty("Content-Type", "application/octet-stream");
                        conn.setRequestProperty("Content-Length", String.valueOf(imgBytes.length));
                        OutputStream os = conn.openOutputStream();
                        os.write(imgBytes);
                        os.flush();
                        os.close();

                        int respCode = conn.getResponseCode();
                        handleQRResponse(conn, respCode, returnTarget);
                    } catch (Exception ex) {
                        if (url.startsWith("https://")) {
                            String httpUrl = "http://" + url.substring(8);
                            conn = (HttpConnection) Connector.open(httpUrl, Connector.READ_WRITE);
                            conn.setRequestMethod(HttpConnection.POST);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            conn.setRequestProperty("Content-Type", "application/octet-stream");
                            conn.setRequestProperty("Content-Length", String.valueOf(imgBytes.length));
                            OutputStream os = conn.openOutputStream();
                            os.write(imgBytes);
                            os.flush();
                            os.close();

                            int respCode = conn.getResponseCode();
                            handleQRResponse(conn, respCode, returnTarget);
                        } else {
                            throw ex;
                        }
                    }
                } catch (Exception e) {
                    showAlert("QR Error", "Connection failed: " + e.getMessage(), AlertType.ERROR, returnTarget);
                }
            }
        });
        t.start();
    }

    private void handleQRResponse(HttpConnection conn, int respCode, Displayable returnTarget) throws IOException {
        InputStream is = null;
        try {
            if (respCode >= 400) {
                is = conn.openDataInputStream();
            } else {
                is = conn.openInputStream();
            }
        } catch (Exception e) {
            try { is = conn.openInputStream(); } catch (Exception ignored) {}
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[512];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            is.close();
        }
        conn.close();

        String json = new String(baos.toByteArray(), "UTF-8");
        String status = extractJsonField(json, "status");

        if ("ok".equals(status)) {
            String code = extractJsonField(json, "code");
            if (code != null && code.length() > 0) {
                try {
                    AlertType.CONFIRMATION.playSound(display);
                } catch (Exception ignored) {}
                lookupItemByCode(code, mainForm);
                return;
            }
        }

        String msg = extractJsonField(json, "message");
        if (msg == null || msg.length() == 0) {
            msg = "No QR code could be detected in the image. Please make sure the code is centered and in focus.";
        }
        showAlert("Scan Result", msg, AlertType.WARNING, returnTarget);
    }

    private void readAndDecodePhotoFile(final String photoUrl) {
        final Form readingForm = new Form("Reading Photo");
        readingForm.append(new StringItem("Status:", "Reading image file for QR decoding..."));
        readingForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(readingForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    FileConnection fc = (FileConnection) Connector.open(photoUrl, Connector.READ);
                    long sz = fc.fileSize();
                    if (sz > 4 * 1024 * 1024) {
                        fc.close();
                        showAlert("Photo Too Large", "Photo exceeds 4 MB. Please select a smaller photo.", AlertType.WARNING, findForm != null ? (Displayable) findForm : mainForm);
                        return;
                    }
                    InputStream is = fc.openInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                    is.close();
                    fc.close();
                    byte[] imgBytes = baos.toByteArray();
                    sendImageToDecodeQR(imgBytes, findForm != null ? (Displayable) findForm : mainForm);
                } catch (Exception ex) {
                    showAlert("Read Error", "Could not read photo: " + ex.getMessage(), AlertType.ERROR, findForm != null ? (Displayable) findForm : mainForm);
                }
            }
        });
        t.start();
    }

    private void openPhotoGalleryForQR() {
        galleryForQR = true;
        browserForQR = false;
        openPhotoGallery();
    }

    // ------------------------------------------------------------------------
    // PHOTO GALLERY (FAST ASYNCHRONOUS STORAGE SCANNER)
    // ------------------------------------------------------------------------
    private void openPhotoGallery() {
        final Form loadingForm = new Form("Photos");
        loadingForm.append(new StringItem("Status:", "Searching camera storage for photos..."));
        loadingForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(loadingForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                scanPhotosBackground();
            }
        });
        t.start();
    }

    private void scanPhotosBackground() {
        galleryEntries.removeAllElements();
        galleryList = new List("Choose Photo", List.IMPLICIT);
        galleryList.addCommand(cmdSelect);
        galleryList.addCommand(cmdBack);
        galleryList.setCommandListener(this);

        // First item is always manual folder navigation
        galleryList.append("Browse Storage Folders", null);
        galleryEntries.addElement("ACTION_BROWSE");

        Vector allPhotos = new Vector();

        try {
            Enumeration roots = FileSystemRegistry.listRoots();
            Vector rootList = new Vector();
            while (roots.hasMoreElements()) {
                rootList.addElement((String) roots.nextElement());
            }

            // Common camera and picture subdirectories on Symbian/J2ME
            String[] subDirs = {
                "DCIM/100NOKIA/",
                "DCIM/",
                "Images/Camera/",
                "Images/",
                "Data/Images/",
                "Pictures/",
                "My Pictures/",
                "Photos/",
                ""
            };

            for (int i = 0; i < rootList.size(); i++) {
                String root = (String) rootList.elementAt(i);
                String rootUrl = "file:///" + root;
                if (!rootUrl.endsWith("/")) rootUrl += "/";

                for (int j = 0; j < subDirs.length; j++) {
                    String target = rootUrl + subDirs[j];
                    scanSingleFolder(target, allPhotos);
                    if (allPhotos.size() >= 120) break;
                }
                if (allPhotos.size() >= 120) break;
            }

            // Sort allPhotos descending by timestamp, then filename descending (newest first)
            int total = allPhotos.size();
            for (int i = 0; i < total - 1; i++) {
                int maxIdx = i;
                for (int j = i + 1; j < total; j++) {
                    PhotoItem a = (PhotoItem) allPhotos.elementAt(maxIdx);
                    PhotoItem b = (PhotoItem) allPhotos.elementAt(j);
                    if (b.time > a.time) {
                        maxIdx = j;
                    } else if (b.time == a.time && b.name.compareTo(a.name) > 0) {
                        maxIdx = j;
                    }
                }
                if (maxIdx != i) {
                    Object tmp = allPhotos.elementAt(i);
                    allPhotos.setElementAt(allPhotos.elementAt(maxIdx), i);
                    allPhotos.setElementAt(tmp, maxIdx);
                }
            }

            for (int i = 0; i < allPhotos.size() && i < 100; i++) {
                PhotoItem pi = (PhotoItem) allPhotos.elementAt(i);
                if (!galleryEntries.contains(pi.url)) {
                    galleryEntries.addElement(pi.url);
                    galleryList.append(pi.name, null);
                }
            }
        } catch (Exception e) {}

        display.setCurrent(galleryList);
    }

    private void scanSingleFolder(String folderUrl, Vector dest) {
        try {
            FileConnection fc = (FileConnection) Connector.open(folderUrl, Connector.READ);
            if (fc.exists() && fc.isDirectory()) {
                Enumeration list = fc.list();
                Vector foundNames = new Vector();
                while (list.hasMoreElements()) {
                    String name = (String) list.nextElement();
                    if (!name.endsWith("/")) {
                        String lower = name.toLowerCase();
                        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) {
                            foundNames.addElement(name);
                        }
                    }
                }
                fc.close();

                for (int i = 0; i < foundNames.size(); i++) {
                    String fname = (String) foundNames.elementAt(i);
                    String fullUrl = folderUrl + fname;
                    long modTime = 0;
                    try {
                        FileConnection itemFc = (FileConnection) Connector.open(fullUrl, Connector.READ);
                        modTime = itemFc.lastModified();
                        itemFc.close();
                    } catch (Exception ex) {}
                    dest.addElement(new PhotoItem(fullUrl, fname, modTime));
                    if (dest.size() >= 120) break;
                }
            } else {
                fc.close();
            }
        } catch (Exception ex) {}
    }

    private void handleGallerySelect() {
        int idx = galleryList.getSelectedIndex();
        if (idx < 0 || idx >= galleryEntries.size()) return;

        if (idx == 0) {
            // First item is Browse Storage Folders
            if (galleryForQR) {
                galleryForQR = false;
                browserForQR = true;
            }
            openFileBrowser(null);
            return;
        }

        String selectedUrl = (String) galleryEntries.elementAt(idx);
        if (galleryForQR) {
            galleryForQR = false;
            readAndDecodePhotoFile(selectedUrl);
            return;
        }

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
    // FILE BROWSER (STORAGE NAVIGATION)
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
                            browserList.append("/" + name.substring(0, name.length() - 1), null);
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
        if (root.startsWith("C:") || root.startsWith("c:")) return "Phone Memory C:";
        if (root.startsWith("E:") || root.startsWith("e:")) return "Mass Memory E:";
        if (root.startsWith("F:") || root.startsWith("f:")) return "Memory Card F:";
        if (root.startsWith("D:") || root.startsWith("d:")) return "RAM Disk D:";
        if (root.startsWith("Z:") || root.startsWith("z:")) return "ROM Z:";
        return "Drive: " + root;
    }

    private void handleBrowserSelect() {
        int idx = browserList.getSelectedIndex();
        if (idx < 0 || idx >= currentEntries.size()) return;

        String selectedUrl = (String) currentEntries.elementAt(idx);
        if (selectedUrl.endsWith("/")) {
            openFileBrowser(selectedUrl);
        } else {
            if (browserForQR) {
                browserForQR = false;
                readAndDecodePhotoFile(selectedUrl);
                return;
            }
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
            if (browserForQR) {
                browserForQR = false;
                display.setCurrent(findForm != null ? (Displayable) findForm : mainForm);
            } else {
                display.setCurrent(mainForm);
            }
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

                    HttpConnection conn = null;
                    try {
                        conn = (HttpConnection) Connector.open(url, Connector.READ);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        int code = conn.getResponseCode();
                        if (code != 200) {
                            conn.close();
                            showAlert("Public Files", "Server error HTTP " + code, AlertType.ERROR, mainForm);
                            return;
                        }
                    } catch (Exception ex) {
                        if (url.startsWith("https://")) {
                            String httpUrl = "http://" + url.substring(8);
                            conn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            int code = conn.getResponseCode();
                            if (code != 200) {
                                conn.close();
                                showAlert("Public Files", "Server error HTTP " + code, AlertType.ERROR, mainForm);
                                return;
                            }
                        } else {
                            throw ex;
                        }
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

                    publicList = new List("Public Files: " + publicItems.size() + " items", List.IMPLICIT);
                    publicList.addCommand(cmdSelect);
                    publicList.addCommand(cmdBack);
                    publicList.setCommandListener(NullhostMIDlet.this);

                    for (int i = 0; i < publicItems.size(); i++) {
                        UploadRecord rec = (UploadRecord) publicItems.elementAt(i);
                        publicList.append(rec.filename + " - " + formatSize(rec.size), null);
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
        if (rec.hasPassword && (rec.password == null || rec.password.length() == 0)) {
            promptFilePassword(rec, rec.id, publicList, false);
        } else {
            showItemDetails(rec, publicList, false);
        }
    }

    // ------------------------------------------------------------------------
    // MY UPLOAD HISTORY (SERVER ACCOUNT SYNC + LOCAL SESSION)
    // ------------------------------------------------------------------------
    private void openMyUploads() {
        if (accountId != null && accountId.length() > 0) {
            final Form loadForm = new Form("Upload History");
            loadForm.append(new StringItem("Status:", "Fetching account upload history..."));
            loadForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
            display.setCurrent(loadForm);

            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        String base = serverUrl.trim();
                        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                        String url = base + "/api/mobile/items?account_id=" + urlEncode(accountId);

                        HttpConnection conn = null;
                        try {
                            conn = (HttpConnection) Connector.open(url, Connector.READ);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            conn.setRequestProperty("X-Nullhost-Account", accountId);
                            int code = conn.getResponseCode();
                            if (code != 200) {
                                conn.close();
                                renderUploadHistoryList();
                                return;
                            }
                        } catch (Exception ex) {
                            if (url.startsWith("https://")) {
                                String httpUrl = "http://" + url.substring(8);
                                conn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                                conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                                conn.setRequestProperty("X-Nullhost-Account", accountId);
                                int code = conn.getResponseCode();
                                if (code != 200) {
                                    conn.close();
                                    renderUploadHistoryList();
                                    return;
                                }
                            } else {
                                renderUploadHistoryList();
                                return;
                            }
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
                        Vector serverItems = parseItemsArray(json);

                        for (int i = 0; i < serverItems.size(); i++) {
                            UploadRecord sRec = (UploadRecord) serverItems.elementAt(i);
                            boolean exists = false;
                            for (int j = 0; j < myUploads.size(); j++) {
                                UploadRecord mRec = (UploadRecord) myUploads.elementAt(j);
                                if (sRec.id != null && sRec.id.equals(mRec.id)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                myUploads.addElement(sRec);
                            }
                        }

                        renderUploadHistoryList();
                    } catch (Exception ex) {
                        renderUploadHistoryList();
                    }
                }
            });
            t.start();
        } else {
            renderUploadHistoryList();
        }
    }

    private void renderUploadHistoryList() {
        if (myUploads.isEmpty()) {
            showAlert("Upload History", "No uploads performed or account doesn't have uploads.", AlertType.INFO, mainForm);
            return;
        }

        final List histList = new List("Upload History: " + myUploads.size() + " items", List.IMPLICIT);
        final Command cmdHistSelect = new Command("View", Command.OK, 1);
        final Command cmdHistBack = new Command("Back", Command.BACK, 2);
        histList.addCommand(cmdHistSelect);
        histList.addCommand(cmdHistBack);

        for (int i = 0; i < myUploads.size(); i++) {
            UploadRecord rec = (UploadRecord) myUploads.elementAt(i);
            String title = (rec.hasPassword ? "[Locked] " : "") + rec.filename + " - " + formatSize(rec.size);
            histList.append(title, null);
        }

        histList.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdHistSelect || c == List.SELECT_COMMAND) {
                    int idx = histList.getSelectedIndex();
                    if (idx >= 0 && idx < myUploads.size()) {
                        UploadRecord rec = (UploadRecord) myUploads.elementAt(idx);
                        if (rec.hasPassword && (rec.password == null || rec.password.length() == 0)) {
                            promptFilePassword(rec, rec.id, histList, true);
                        } else {
                            showItemDetails(rec, histList, true);
                        }
                    }
                } else if (c == cmdHistBack) {
                    display.setCurrent(mainForm);
                }
            }
        });

        display.setCurrent(histList);
    }

    // ------------------------------------------------------------------------
    // ITEM DETAILS SCREEN (WITH DOWNLOAD BUTTON & TEXT VIEWER, NO CHECKMARK)
    // ------------------------------------------------------------------------
    private void showItemDetails(final UploadRecord rec, final Displayable backTarget, final boolean isMyUpload) {
        final Form f = new Form("File Details");

        // QR Code centered without label offset
        if (rec.qrImage != null) {
            f.append(new ImageItem(null, rec.qrImage, Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER, "QR Code"));
        }

        f.append(new StringItem("File Name:", rec.filename));
        if (rec.id != null && rec.id.length() > 0) {
            f.append(new StringItem("Item Code:", rec.id));
        }
        f.append(new StringItem("File Size:", formatSize(rec.size)));
        if (rec.expiry != null && rec.expiry.length() > 0) {
            f.append(new StringItem("Retention:", rec.expiry));
        }
        if (rec.burn != null && rec.burn.length() > 0 && !rec.burn.equals("Disabled")) {
            f.append(new StringItem("Burn Limit:", rec.burn));
        }
        if (rec.url != null && rec.url.length() > 0) {
            f.append(new StringItem("Short URL:", rec.url));
        }
        if (rec.rawUrl != null && rec.rawUrl.length() > 0) {
            f.append(new StringItem("Raw URL:", rec.rawUrl));
        }

        f.append(new Spacer(10, 6));

        // 1. Text Actions (if text file or paste)
        if (isTextItem(rec)) {
            // Button to open our clean in-app text viewer ("sun oma")
            StringItem btnViewText = new StringItem(null, "View Text Content", Item.BUTTON);
            btnViewText.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
            btnViewText.setDefaultCommand(cmdItemAction);
            btnViewText.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                    openTextContent(rec, f, false);
                }
            });
            f.append(btnViewText);

            // Button to open in the phone's native text editor ("oikeassa puhelimen tekstieditorissa")
            StringItem btnNativeEditor = new StringItem(null, "Open in Phone Editor", Item.BUTTON);
            btnNativeEditor.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
            btnNativeEditor.setDefaultCommand(cmdItemAction);
            btnNativeEditor.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                    openTextContent(rec, f, true);
                }
            });
            f.append(btnNativeEditor);
        }

        // 2. Prominent Download Button (triggers native Symbian download manager)
        StringItem btnDownload = new StringItem(null, "Download File", Item.BUTTON);
        btnDownload.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnDownload.setDefaultCommand(cmdItemAction);
        btnDownload.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                triggerFileDownload(rec, f);
            }
        });
        f.append(btnDownload);

        // 3. Open in Browser Button
        StringItem btnOpenBrowser = new StringItem(null, "Open in Browser", Item.BUTTON);
        btnOpenBrowser.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnOpenBrowser.setDefaultCommand(cmdItemAction);
        btnOpenBrowser.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                if (rec.url != null && rec.url.length() > 0) {
                    try {
                        platformRequest(rec.url);
                    } catch (Exception ex) {
                        showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, f);
                    }
                }
            }
        });
        f.append(btnOpenBrowser);

        // 4. Delete Button if viewing an item from My Uploads
        if (isMyUpload) {
            StringItem btnDel = new StringItem(null, "Delete File", Item.BUTTON);
            btnDel.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
            btnDel.setDefaultCommand(cmdItemAction);
            btnDel.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                    deleteUploadedItem(rec);
                }
            });
            f.append(btnDel);
        }

        // Softkeys: Back and Options (NO Command.OK -> NO green checkmark on Belle FP2!)
        final Command cmdDetailsBack = new Command("Back", Command.BACK, 1);
        final Command cmdDownloadMenu = new Command("Download File", Command.SCREEN, 2);
        final Command cmdOpenMenu = new Command("Open in Browser", Command.SCREEN, 3);
        final Command cmdNativeMenu = isTextItem(rec) ? new Command("Open in Phone Editor", Command.SCREEN, 4) : null;
        final Command cmdDelMenu = isMyUpload ? new Command("Delete File", Command.SCREEN, 5) : null;

        f.addCommand(cmdDetailsBack);
        f.addCommand(cmdDownloadMenu);
        f.addCommand(cmdOpenMenu);
        if (cmdNativeMenu != null) f.addCommand(cmdNativeMenu);
        if (cmdDelMenu != null) f.addCommand(cmdDelMenu);

        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdDetailsBack) {
                    display.setCurrent(backTarget);
                } else if (c == cmdDownloadMenu) {
                    triggerFileDownload(rec, f);
                } else if (c == cmdOpenMenu) {
                    if (rec.url != null && rec.url.length() > 0) {
                        try {
                            platformRequest(rec.url);
                        } catch (Exception ex) {
                            showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, f);
                        }
                    }
                } else if (c == cmdNativeMenu) {
                    openTextContent(rec, f, true);
                } else if (c == cmdDelMenu) {
                    deleteUploadedItem(rec);
                }
            }
        });

        // Load QR code in background if missing
        if (rec.qrImage == null && rec.qrUrl != null && rec.qrUrl.length() > 0) {
            Thread qrThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        HttpConnection conn = null;
                        try {
                            conn = (HttpConnection) Connector.open(rec.qrUrl, Connector.READ);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            if (conn.getResponseCode() == 200) {
                                InputStream is = conn.openInputStream();
                                rec.qrImage = Image.createImage(is);
                                is.close();
                            }
                            conn.close();
                        } catch (Exception ex) {
                            if (rec.qrUrl.startsWith("https://")) {
                                String httpUrl = "http://" + rec.qrUrl.substring(8);
                                conn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                                conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                                if (conn.getResponseCode() == 200) {
                                    InputStream is = conn.openInputStream();
                                    rec.qrImage = Image.createImage(is);
                                    is.close();
                                }
                                conn.close();
                            }
                        }
                        if (rec.qrImage != null) {
                            showItemDetails(rec, backTarget, isMyUpload);
                        }
                    } catch (Exception ex) {}
                }
            });
            qrThread.start();
        }

        display.setCurrent(f);
    }

    private void triggerFileDownload(UploadRecord rec, Form parentForm) {
        String target = (rec.rawUrl != null && rec.rawUrl.length() > 0) ? rec.rawUrl :
                       ((rec.downloadUrl != null && rec.downloadUrl.length() > 0) ? rec.downloadUrl : rec.url);
        if (target != null && target.length() > 0) {
            if (rec.password != null && rec.password.length() > 0 && target.indexOf("password=") == -1) {
                target += (target.indexOf('?') != -1 ? "&" : "?") + "password=" + urlEncode(rec.password);
            }
            try {
                platformRequest(target);
            } catch (Exception ex) {
                showAlert("Download Error", "Could not start download: " + ex.getMessage(), AlertType.ERROR, parentForm);
            }
        }
    }

    private void openTextContent(final UploadRecord rec, final Form parentForm, final boolean launchNative) {
        if (rec.content != null && rec.content.length() > 0) {
            if (launchNative) {
                openInNativePhoneEditor(rec, rec.content, parentForm);
            } else {
                showCustomTextViewer(rec, rec.content, parentForm);
            }
            return;
        }

        final Form loadForm = new Form("Loading Text");
        loadForm.append(new StringItem("Status:", "Fetching text content..."));
        loadForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(loadForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String fetchUrl = (rec.rawUrl != null && rec.rawUrl.length() > 0) ? rec.rawUrl : rec.downloadUrl;
                    if (fetchUrl == null || fetchUrl.length() == 0) {
                        showAlert("Error", "No text URL available.", AlertType.ERROR, parentForm);
                        return;
                    }

                    HttpConnection conn = null;
                    try {
                        conn = (HttpConnection) Connector.open(fetchUrl, Connector.READ);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        int respCode = conn.getResponseCode();
                        if (respCode != 200) {
                            conn.close();
                            showAlert("Error", "Server returned HTTP " + respCode, AlertType.ERROR, parentForm);
                            return;
                        }
                    } catch (Exception ex) {
                        if (fetchUrl.startsWith("https://")) {
                            String httpUrl = "http://" + fetchUrl.substring(8);
                            conn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            int respCode = conn.getResponseCode();
                            if (respCode != 200) {
                                conn.close();
                                showAlert("Error", "Server returned HTTP " + respCode, AlertType.ERROR, parentForm);
                                return;
                            }
                        } else {
                            throw ex;
                        }
                    }

                    InputStream is = conn.openInputStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[512];
                    int len;
                    int total = 0;
                    while ((len = is.read(buf)) != -1) {
                        baos.write(buf, 0, len);
                        total += len;
                        if (total > 65536) break; // 64 KB safety limit for vintage mobile RAM
                    }
                    is.close();
                    conn.close();

                    String text = new String(baos.toByteArray(), "UTF-8");
                    rec.content = text;
                    if (launchNative) {
                        openInNativePhoneEditor(rec, text, parentForm);
                    } else {
                        showCustomTextViewer(rec, text, parentForm);
                    }
                } catch (Exception e) {
                    showAlert("Error", "Could not load text: " + e.getMessage(), AlertType.ERROR, parentForm);
                }
            }
        });
        t.start();
    }

    private void showCustomTextViewer(final UploadRecord rec, final String text, final Displayable backTarget) {
        final Form viewForm = new Form(rec.filename != null ? rec.filename : "Text Viewer");

        // Clean readable text view (native LCDUI word-wrapping & kinetic scroll)
        StringItem textItem = new StringItem(null, text);
        textItem.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        viewForm.append(textItem);

        viewForm.append(new Spacer(10, 8));

        // Action button to launch native phone editor
        StringItem btnPhone = new StringItem(null, "Open in Phone Editor", Item.BUTTON);
        btnPhone.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnPhone.setDefaultCommand(cmdItemAction);
        btnPhone.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                openInNativePhoneEditor(rec, text, viewForm);
            }
        });
        viewForm.append(btnPhone);

        // Action button to edit in editable TextBox (if desired)
        StringItem btnEdit = new StringItem(null, "Edit in App", Item.BUTTON);
        btnEdit.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnEdit.setDefaultCommand(cmdItemAction);
        btnEdit.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                showEditableTextBox(rec.filename, text, viewForm);
            }
        });
        viewForm.append(btnEdit);

        final Command cmdViewerBack = new Command("Back", Command.BACK, 1);
        final Command cmdNative = new Command("Open in Phone Editor", Command.SCREEN, 2);
        final Command cmdEdit = new Command("Edit in App", Command.SCREEN, 3);

        viewForm.addCommand(cmdViewerBack);
        viewForm.addCommand(cmdNative);
        viewForm.addCommand(cmdEdit);

        viewForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdViewerBack) {
                    display.setCurrent(backTarget);
                } else if (c == cmdNative) {
                    openInNativePhoneEditor(rec, text, viewForm);
                } else if (c == cmdEdit) {
                    showEditableTextBox(rec.filename, text, viewForm);
                }
            }
        });

        display.setCurrent(viewForm);
    }

    private void showEditableTextBox(String title, String text, final Displayable backTarget) {
        int maxChars = text.length() > 2048 ? text.length() + 1024 : 2048;
        TextBox tb = new TextBox(title != null ? title : "Edit Text", text, maxChars, TextField.ANY);
        final Command cmdTbBack = new Command("Done", Command.BACK, 1);
        tb.addCommand(cmdTbBack);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdTbBack) {
                    display.setCurrent(backTarget);
                }
            }
        });
        display.setCurrent(tb);
    }

    private void openInNativePhoneEditor(UploadRecord rec, String text, Form parentForm) {
        String fname = (rec.filename != null && rec.filename.length() > 0) ? rec.filename : "paste.txt";
        if (!fname.toLowerCase().endsWith(".txt")) {
            fname += ".txt";
        }

        // Try writing to device storage and platformRequest() it
        String[] candidateDirs = {"file:///E:/Data/", "file:///E:/", "file:///C:/Data/", "file:///C:/"};
        String savedFileUrl = null;

        for (int i = 0; i < candidateDirs.length; i++) {
            try {
                String targetUrl = candidateDirs[i] + fname;
                FileConnection fc = (FileConnection) Connector.open(targetUrl, Connector.READ_WRITE);
                if (fc.exists()) {
                    fc.delete();
                }
                fc.create();
                OutputStream os = fc.openOutputStream();
                os.write(text.getBytes("UTF-8"));
                os.flush();
                os.close();
                fc.close();
                savedFileUrl = targetUrl;
                break;
            } catch (Exception ex) {}
        }

        if (savedFileUrl != null) {
            try {
                boolean launched = platformRequest(savedFileUrl);
                if (launched) {
                    return;
                }
            } catch (Exception ex) {}
        }

        // Fallback: use downloadUrl/rawUrl which Symbian browser/download manager prompts to Open with Text Editor
        String fallback = (rec.downloadUrl != null && rec.downloadUrl.length() > 0) ? rec.downloadUrl : rec.rawUrl;
        if (fallback != null && fallback.length() > 0) {
            try {
                platformRequest(fallback);
            } catch (Exception e) {
                showAlert("Editor Error", "Could not open in phone editor: " + e.getMessage(), AlertType.ERROR, parentForm);
            }
        }
    }

    private static boolean isTextItem(UploadRecord rec) {
        if (rec == null) return false;
        if (rec.type != null && rec.type.equalsIgnoreCase("paste")) return true;
        String fn = rec.filename != null ? rec.filename.toLowerCase() : "";
        if (fn.equals("paste.txt")) return true;
        return fn.endsWith(".txt") || fn.endsWith(".log") || fn.endsWith(".py") ||
               fn.endsWith(".java") || fn.endsWith(".c") || fn.endsWith(".cpp") ||
               fn.endsWith(".h") || fn.endsWith(".html") || fn.endsWith(".htm") ||
               fn.endsWith(".css") || fn.endsWith(".js") || fn.endsWith(".json") ||
               fn.endsWith(".xml") || fn.endsWith(".md") || fn.endsWith(".ini") ||
               fn.endsWith(".conf") || fn.endsWith(".sh");
    }

    // ------------------------------------------------------------------------
    // SERVER FILE DELETION
    // ------------------------------------------------------------------------
    private void deleteUploadedItem(final UploadRecord rec) {
        final Form delForm = new Form("Deleting File");
        delForm.append(new StringItem("Status:", "Deleting " + rec.filename + " from server..."));
        delForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(delForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String delTarget = base + "/api/mobile/delete";

                    HttpConnection conn = null;
                    String postData = "id=" + rec.id;
                    if (accountId.length() > 0) {
                        postData += "&account_id=" + accountId;
                    }

                    try {
                        conn = (HttpConnection) Connector.open(delTarget, Connector.READ_WRITE);
                        conn.setRequestMethod(HttpConnection.POST);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        if (accountId.length() > 0) {
                            conn.setRequestProperty("X-Nullhost-Account", accountId);
                        }
                        OutputStream os = conn.openOutputStream();
                        os.write(postData.getBytes("UTF-8"));
                        os.flush();
                        os.close();
                    } catch (Exception ex) {
                        if (delTarget.startsWith("https://")) {
                            conn = (HttpConnection) Connector.open("http://" + delTarget.substring(8), Connector.READ_WRITE);
                            conn.setRequestMethod(HttpConnection.POST);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                            if (accountId.length() > 0) {
                                conn.setRequestProperty("X-Nullhost-Account", accountId);
                            }
                            OutputStream os = conn.openOutputStream();
                            os.write(postData.getBytes("UTF-8"));
                            os.flush();
                            os.close();
                        } else {
                            throw ex;
                        }
                    }

                    int code = conn.getResponseCode();
                    conn.close();

                    if (code == 200) {
                        myUploads.removeElement(rec);
                        updateMainFormStatus();
                        showAlert("File Deleted", rec.filename + " was deleted from server.", AlertType.CONFIRMATION, mainForm);
                    } else {
                        showAlert("Delete Failed", "Server returned HTTP " + code, AlertType.ERROR, mainForm);
                    }
                } catch (Exception ex) {
                    showAlert("Delete Error", "Could not delete: " + ex.getMessage(), AlertType.ERROR, mainForm);
                }
            }
        });
        t.start();
    }

    // ------------------------------------------------------------------------
    // MULTIPART UPLOAD & LIVE QR GENERATION
    // ------------------------------------------------------------------------
    private void startUploadThread() {
        if (selectedFilePath == null) {
            showAlert("No File Selected", "Please choose a photo or file first.", AlertType.WARNING, mainForm);
            return;
        }

        // Enforce 24-hour limit for non-account users
        if (expiryIndex >= 2 && accountId.length() == 0) {
            expiryIndex = 1; // Fallback to 24h
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
                HttpConnection qrConn = null;
                try {
                    qrConn = (HttpConnection) Connector.open(record.qrUrl, Connector.READ);
                    qrConn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                    if (qrConn.getResponseCode() == 200) {
                        InputStream qris = qrConn.openInputStream();
                        record.qrImage = Image.createImage(qris);
                        qris.close();
                    }
                    qrConn.close();
                } catch (Exception ex) {
                    if (record.qrUrl.startsWith("https://")) {
                        String httpUrl = "http://" + record.qrUrl.substring(8);
                        qrConn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                        qrConn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        if (qrConn.getResponseCode() == 200) {
                            InputStream qris = qrConn.openInputStream();
                            record.qrImage = Image.createImage(qris);
                            qris.close();
                        }
                        qrConn.close();
                    }
                }
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

            String expStr = extractJsonField(respStr, "expires_at");
            if (expStr != null) {
                try { record.expiresAt = Long.parseLong(expStr); } catch (Exception e) {}
            }

            record.url = extractJsonField(respStr, "url");
            record.rawUrl = extractJsonField(respStr, "raw_url");
            record.downloadUrl = extractJsonField(respStr, "download_url");
            record.qrUrl = extractJsonField(respStr, "qr_url");
            record.deleteUrl = extractJsonField(respStr, "delete_url");
            record.expiry = formatRetentionDisplay(EXPIRY_LABELS[expiryIndex], record.expiresAt);
            record.burn = BURN_LABELS[burnIndex];

            if (record.url == null) record.url = targetUrl;
            if (record.rawUrl == null) record.rawUrl = record.url;
            if (record.downloadUrl == null) record.downloadUrl = record.rawUrl;
            if (record.qrUrl == null && record.url != null) record.qrUrl = record.url + "/qr.png?size=5";

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
    // UPLOAD SUCCESS SCREEN (PROMINENT QR CODE & DOWNLOAD BUTTON, NO CHECKMARK)
    // ------------------------------------------------------------------------
    private void showSuccessScreen(final UploadRecord record) {
        final Form successForm = new Form("Upload Successful");

        if (record.qrImage != null) {
            successForm.append(new ImageItem(null, record.qrImage, Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER, "Scan to Download"));
        }

        successForm.append(new StringItem("Item Code:", record.id));
        successForm.append(new StringItem("File Name:", record.filename));
        successForm.append(new StringItem("File Size:", formatSize(record.size)));
        successForm.append(new StringItem("Retention:", record.expiry));
        successForm.append(new StringItem("Burn Limit:", record.burn));
        successForm.append(new StringItem("Short URL:", record.url));
        successForm.append(new StringItem("Direct Raw Link:", record.rawUrl));

        successForm.append(new Spacer(10, 6));

        // In-form action buttons (NO Command.OK -> NO checkmark button on Belle toolbar!)
        if (isTextItem(record)) {
            StringItem btnViewText = new StringItem(null, "View Text Content", Item.BUTTON);
            btnViewText.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
            btnViewText.setDefaultCommand(cmdItemAction);
            btnViewText.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                    openTextContent(record, successForm, false);
                }
            });
            successForm.append(btnViewText);

            StringItem btnNative = new StringItem(null, "Open in Phone Editor", Item.BUTTON);
            btnNative.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
            btnNative.setDefaultCommand(cmdItemAction);
            btnNative.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                    openTextContent(record, successForm, true);
                }
            });
            successForm.append(btnNative);
        }

        StringItem btnDownload = new StringItem(null, "Download File", Item.BUTTON);
        btnDownload.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnDownload.setDefaultCommand(cmdItemAction);
        btnDownload.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                triggerFileDownload(record, successForm);
            }
        });
        successForm.append(btnDownload);

        StringItem btnDel = new StringItem(null, "Delete File", Item.BUTTON);
        btnDel.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnDel.setDefaultCommand(cmdItemAction);
        btnDel.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                deleteUploadedItem(record);
            }
        });
        successForm.append(btnDel);

        StringItem btnOpenBrowser = new StringItem(null, "Open in Browser", Item.BUTTON);
        btnOpenBrowser.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnOpenBrowser.setDefaultCommand(cmdItemAction);
        btnOpenBrowser.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                if (record.url != null && record.url.length() > 0) {
                    try {
                        platformRequest(record.url);
                    } catch (Exception ex) {
                        showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, successForm);
                    }
                }
            }
        });
        successForm.append(btnOpenBrowser);

        StringItem btnUploadMore = new StringItem(null, "Upload Another File", Item.BUTTON);
        btnUploadMore.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnUploadMore.setDefaultCommand(cmdItemAction);
        btnUploadMore.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                selectedFilePath = null;
                selectedFileSize = 0;
                updateMainFormStatus();
                display.setCurrent(mainForm);
            }
        });
        successForm.append(btnUploadMore);

        final Command cmdDone = new Command("Home", Command.BACK, 1);
        final Command cmdNew = new Command("Upload Another", Command.SCREEN, 2);
        final Command cmdOpen = new Command("Open in Browser", Command.SCREEN, 3);
        final Command cmdDel = new Command("Delete File", Command.SCREEN, 4);
        final Command cmdExitApp = new Command("Exit", Command.EXIT, 5);

        successForm.addCommand(cmdDone);
        successForm.addCommand(cmdNew);
        successForm.addCommand(cmdOpen);
        successForm.addCommand(cmdDel);
        successForm.addCommand(cmdExitApp);

        successForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdDone) {
                    updateMainFormStatus();
                    display.setCurrent(mainForm);
                } else if (c == cmdNew) {
                    selectedFilePath = null;
                    selectedFileSize = 0;
                    updateMainFormStatus();
                    display.setCurrent(mainForm);
                } else if (c == cmdOpen) {
                    try {
                        platformRequest(record.url);
                    } catch (Exception ex) {
                        showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, successForm);
                    }
                } else if (c == cmdDel) {
                    deleteUploadedItem(record);
                } else if (c == cmdExitApp) {
                    destroyApp(true);
                    notifyDestroyed();
                }
            }
        });

        display.setCurrent(successForm);
    }

    // ------------------------------------------------------------------------
    // CONSOLIDATED SETTINGS FORM (SERVER + UPLOAD PARAMETERS + RMS PERSISTENCE)
    // ------------------------------------------------------------------------
    private void openSettingsForm() {
        settingsForm = new Form("Settings");

        optServerUrl = new TextField("Server URL:", serverUrl, 128, TextField.URL);
        settingsForm.append(optServerUrl);

        optExpiry = new ChoiceGroup("Retention Time:", Choice.EXCLUSIVE, EXPIRY_LABELS, null);
        optExpiry.setSelectedIndex(expiryIndex, true);
        settingsForm.append(optExpiry);

        optBurn = new ChoiceGroup("Burn After Reading:", Choice.EXCLUSIVE, BURN_LABELS, null);
        optBurn.setSelectedIndex(burnIndex, true);
        settingsForm.append(optBurn);

        optPassword = new TextField("Password Optional:", customPassword, 32, TextField.ANY);
        settingsForm.append(optPassword);

        optSlug = new TextField("Custom Link or Slug:", customSlug, 32, TextField.ANY);
        settingsForm.append(optSlug);

        optAccount = new TextField("Account ID (Optional):", accountId, 32, TextField.ANY);
        settingsForm.append(optAccount);

        settingsForm.addCommand(cmdSaveSettings);
        settingsForm.addCommand(cmdBack);
        settingsForm.setCommandListener(this);

        display.setCurrent(settingsForm);
    }

    private void saveSettings() {
        String entered = optServerUrl.getString().trim();
        if (entered.endsWith("/")) {
            entered = entered.substring(0, entered.length() - 1);
        }
        if (entered.length() > 0) {
            serverUrl = entered;
        }

        int selExp = optExpiry.getSelectedIndex();
        String enteredAcc = optAccount.getString().trim();

        // Enforce 24-hour retention rule: indices 2 (7d), 3 (30d), 4 (never) require account ID
        if (selExp >= 2 && enteredAcc.length() == 0) {
            optExpiry.setSelectedIndex(1, true); // Reset to 24 Hours
            showAlert("Account Required", "Retention over 24 hours is only available for registered accounts. Please enter your Account ID in settings.", AlertType.WARNING, settingsForm);
            return;
        }

        expiryIndex = optExpiry.getSelectedIndex();
        burnIndex = optBurn.getSelectedIndex();
        customPassword = optPassword.getString().trim();
        customSlug = optSlug.getString().trim();
        accountId = enteredAcc;

        savePersistentSettings();

        updateMainFormStatus();
        showAlert("Settings Saved", "Settings and Account ID saved successfully.", AlertType.CONFIRMATION, mainForm);
    }

    private void showAbout() {
        long totalMem = Runtime.getRuntime().totalMemory() / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024;

        Form f = new Form("About Nullhost");
        f.append(new StringItem("Nullhost Universal J2ME", "Version " + APP_VERSION + "\n\n" +
            "Fast, anonymous file and photo hosting for Symbian and vintage phones.\n\n" +
            "Features:\n" +
            "- Scan QR codes directly from saved photos & gallery\n" +
            "- Instant camera photo picker & storage scanner\n" +
            "- Find files by code, URL, or scanned QR\n" +
            "- In-app text viewer & native phone editor launcher\n" +
            "- Direct Symbian file download manager\n" +
            "- Persistent RMS settings & Account ID storage\n" +
            "- Delete files from upload history\n" +
            "- JSR-75 filesystem navigation\n" +
            "- Live centered QR code generation\n" +
            "- Browse public files & upload history\n" +
            "- Direct HMAC mobile uploads\n\n" +
            "Server: " + serverUrl + "\n" +
            "Free Memory: " + freeMem + " KB / " + totalMem + " KB\n" +
            "Platform: " + System.getProperty("microedition.platform") + "\n\n" +
            "Gelaxiz (c) 2026"));
        f.addCommand(cmdBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    private void showTermsOfService() {
        Form f = new Form("Terms of Service");
        f.append(new StringItem("1. Limits:", "By uploading, you agree to these rules. Max size 10 GB per upload. Files over 95 MB chunked. If you don't agree, please do not use the service.\n\n"));
        f.append(new StringItem("2. Responsibility:", "You own whatever you upload. Nullhost is a dumb pipe. We do not actively monitor uploads, but legal notices result in deletion.\n\n"));
        f.append(new StringItem("3. As-Is Service:", "Nullhost is provided as-is without uptime or retention guarantees. Do not use as sole backup for important files.\n\n"));
        f.append(new StringItem("4. Liability:", "Nullhost is not responsible for damages resulting from service usage or file loss.\n\n"));
        f.append(new StringItem("5. Link Reuse Risk:", "Shortcodes may be reused after items expire or are deleted (7-day tombstone cooldown)."));
        f.addCommand(cmdBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    private void showPrivacyPolicy() {
        Form f = new Form("Privacy Policy");
        f.append(new StringItem("1. Data We Host:", "Minimal data only: files and text you upload (stored momentarily until expiration/deletion), session cookies if logged in, basic IP/timestamp web logs.\n\n"));
        f.append(new StringItem("2. How Data Is Used:", "Solely to provide file hosting. We do not sell data, track browsing across sites, or train AI models.\n\n"));
        f.append(new StringItem("3. Data Retention:", "Files permanently deleted upon expiry or burn limit. Server logs routinely purged.\n\n"));
        f.append(new StringItem("4. Third-Party Sharing:", "No sharing with third parties unless compelled by valid legal law enforcement order.\n\n"));
        f.append(new StringItem("5. Cookies:", "Only strictly necessary session cookies. No tracking or ads."));
        f.addCommand(cmdBack);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    private void promptFilePassword(final UploadRecord rec, final String code, final Displayable backTarget, final boolean isMyUpload) {
        final Form pwdForm = new Form("Password Protected");
        pwdForm.append(new StringItem("Notice:", "This file is password protected. Enter the password to view and download."));
        final TextField tfPwd = new TextField("Password:", "", 64, TextField.PASSWORD);
        pwdForm.append(tfPwd);

        final Command cmdUnlock = new Command("Unlock", Command.OK, 1);
        final Command cmdCancel = new Command("Cancel", Command.BACK, 2);
        pwdForm.addCommand(cmdUnlock);
        pwdForm.addCommand(cmdCancel);

        pwdForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdUnlock) {
                    final String entered = tfPwd.getString().trim();
                    if (entered.length() == 0) {
                        showAlert("Password Required", "Please enter the password.", AlertType.WARNING, pwdForm);
                        return;
                    }

                    final Form unlockingForm = new Form("Verifying Password");
                    unlockingForm.append(new StringItem("Status:", "Validating password with server..."));
                    unlockingForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
                    display.setCurrent(unlockingForm);

                    Thread t = new Thread(new Runnable() {
                        public void run() {
                            try {
                                String base = serverUrl.trim();
                                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                                String unlockUrl = base + "/api/mobile/item/" + code + "?password=" + urlEncode(entered);

                                HttpConnection conn = null;
                                try {
                                    conn = (HttpConnection) Connector.open(unlockUrl, Connector.READ);
                                    conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                                    conn.setRequestProperty("X-Nullhost-Password", entered);
                                    int respCode = conn.getResponseCode();
                                    if (respCode == 401 || respCode == 403) {
                                        conn.close();
                                        showAlert("Incorrect Password", "The password you entered is incorrect.", AlertType.ERROR, pwdForm);
                                        return;
                                    }
                                    if (respCode != 200) {
                                        conn.close();
                                        showAlert("Error", "Server returned HTTP " + respCode, AlertType.ERROR, pwdForm);
                                        return;
                                    }
                                } catch (Exception ex) {
                                    if (unlockUrl.startsWith("https://")) {
                                        String httpUrl = "http://" + unlockUrl.substring(8);
                                        conn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                                        conn.setRequestProperty("X-Nullhost-Password", entered);
                                        int respCode = conn.getResponseCode();
                                        if (respCode == 401 || respCode == 403) {
                                            conn.close();
                                            showAlert("Incorrect Password", "The password you entered is incorrect.", AlertType.ERROR, pwdForm);
                                            return;
                                        }
                                        if (respCode != 200) {
                                            conn.close();
                                            showAlert("Error", "Server returned HTTP " + respCode, AlertType.ERROR, pwdForm);
                                            return;
                                        }
                                    } else {
                                        throw ex;
                                    }
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

                                String respJson = new String(baos.toByteArray(), "UTF-8");
                                boolean ok = "true".equals(extractJsonField(respJson, "password_ok"));
                                if (!ok) {
                                    showAlert("Incorrect Password", "The password you entered is incorrect.", AlertType.ERROR, pwdForm);
                                    return;
                                }

                                rec.password = entered;
                                rec.rawUrl = extractJsonField(respJson, "raw_url");
                                rec.downloadUrl = extractJsonField(respJson, "download_url");
                                rec.qrUrl = extractJsonField(respJson, "qr_url");
                                rec.content = extractJsonField(respJson, "content");
                                String sz = extractJsonField(respJson, "size");
                                if (sz != null) {
                                    try { rec.size = Long.parseLong(sz); } catch (Exception ex) {}
                                }

                                showItemDetails(rec, backTarget, isMyUpload);
                            } catch (Exception e) {
                                showAlert("Error", "Connection failed: " + e.getMessage(), AlertType.ERROR, pwdForm);
                            }
                        }
                    });
                    t.start();
                } else if (c == cmdCancel) {
                    display.setCurrent(backTarget);
                }
            }
        });
        display.setCurrent(pwdForm);
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
            rec.type = extractJsonField(objStr, "type");
            String sizeStr = extractJsonField(objStr, "size");
            if (sizeStr != null) {
                try {
                    rec.size = Long.parseLong(sizeStr);
                } catch (Exception ex) {}
            }
            String expStr = extractJsonField(objStr, "expires_at");
            if (expStr != null) {
                try {
                    rec.expiresAt = Long.parseLong(expStr);
                } catch (Exception ex) {}
            }
            rec.url = extractJsonField(objStr, "url");
            rec.rawUrl = extractJsonField(objStr, "raw_url");
            rec.downloadUrl = extractJsonField(objStr, "download_url");
            rec.qrUrl = extractJsonField(objStr, "qr_url");
            if (rec.qrUrl == null && rec.url != null) {
                rec.qrUrl = rec.url + "/qr.png?size=5";
            }
            rec.expiry = formatRetentionDisplay(null, rec.expiresAt);
            rec.hasPassword = "true".equals(extractJsonField(objStr, "has_password"));

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

    private static String formatTimeLeft(long expiresAtSec) {
        if (expiresAtSec <= 0) return "";
        if (expiresAtSec >= 2000000000L) return "Never Expire";
        long nowSec = System.currentTimeMillis() / 1000L;
        long diff = expiresAtSec - nowSec;
        if (diff <= 0) return "Expired";
        long days = diff / 86400L;
        long hours = (diff % 86400L) / 3600L;
        long mins = (diff % 3600L) / 60L;
        if (days > 0) {
            return days + "d " + hours + "h left";
        } else if (hours > 0) {
            return hours + "h " + mins + "m left";
        } else {
            long m = mins > 1L ? mins : 1L;
            return m + "m left";
        }
    }

    private static String formatRetentionDisplay(String label, long expiresAtSec) {
        if (expiresAtSec >= 2000000000L) return "Never Expire";
        String left = formatTimeLeft(expiresAtSec);
        String cleanLabel = label;
        if (cleanLabel != null) {
            int pIdx = cleanLabel.indexOf('(');
            if (pIdx != -1) {
                cleanLabel = cleanLabel.substring(0, pIdx).trim();
            }
            int defIdx = cleanLabel.indexOf("Default");
            if (defIdx != -1) {
                cleanLabel = cleanLabel.substring(0, defIdx).trim() + " " + cleanLabel.substring(defIdx + 7).trim();
                cleanLabel = cleanLabel.trim();
            }
        }
        if (left.length() == 0) {
            return (cleanLabel != null && cleanLabel.length() > 0) ? cleanLabel : "24 Hours";
        }
        if (cleanLabel != null && cleanLabel.length() > 0 && !cleanLabel.equals(left)) {
            return cleanLabel + " (" + left + ")";
        }
        return left;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) {
            return bytes + " B";
        }
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

    private static final String HEX_DIGITS = "0123456789ABCDEF";

    private static String hexByte(int b) {
        return "%" + HEX_DIGITS.charAt((b >> 4) & 0x0F) + HEX_DIGITS.charAt(b & 0x0F);
    }

    private static String urlEncode(String s) {
        if (s == null) return "";
        StringBuffer sbuf = new StringBuffer();
        int len = s.length();
        for (int i = 0; i < len; i++) {
            int ch = s.charAt(i);
            if (('A' <= ch && ch <= 'Z') || ('a' <= ch && ch <= 'z') || ('0' <= ch && ch <= '9') ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '*') {
                sbuf.append((char)ch);
            } else if (ch == ' ') {
                sbuf.append('+');
            } else if (ch <= 0x007F) {
                sbuf.append(hexByte(ch));
            } else if (ch <= 0x07FF) {
                sbuf.append(hexByte(0xC0 | (ch >> 6)));
                sbuf.append(hexByte(0x80 | (ch & 0x3F)));
            } else {
                sbuf.append(hexByte(0xE0 | (ch >> 12)));
                sbuf.append(hexByte(0x80 | ((ch >> 6) & 0x3F)));
                sbuf.append(hexByte(0x80 | (ch & 0x3F)));
            }
        }
        return sbuf.toString();
    }
}
