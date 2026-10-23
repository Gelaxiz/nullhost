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
 * Complete mobile client with native touch buttons, storage explorer,
 * in-app code search, server file deletion, custom text viewer,
 * persistent RMS settings storage, and live centered QR code display.
 * Pure Java 1.4 (Major version 48) - 100% Dependency-Free.
 * Copyright (c) 2026 Gelaxiz. All rights reserved.
 */
public class NullhostMIDlet extends MIDlet implements CommandListener, ItemCommandListener {

    private static final String APP_VERSION = "1.9.6";
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
    private boolean fastUpload = false;        // Fast 1080p compression option

    private static final String[] EXPIRY_LABELS = {"1 Hour", "24 Hours", "7 Days", "30 Days", "Never Expire"};
    private static final String[] EXPIRY_VALS = {"1h", "24h", "7d", "30d", "never"};

    private static final String[] BURN_LABELS = {"Disabled", "1 View", "5 Views", "10 Views"};
    private static final String[] BURN_VALS = {"0", "1", "5", "10"};

    // Current Selection
    private String selectedFilePath = null;
    private long selectedFileSize = 0;

    // Direct Text Paste Creator
    private StringItem btnCreatePaste;

    // History Export
    private Command cmdExportHistory;

    // User Upload History (Session Records)
    private Vector myUploads = new Vector();

    // UI - Main Form
    private Form mainForm;
    private StringItem fileStatusItem;
    private ImageItem previewImageItem;
    private StringItem btnSelectPhoto;
    private StringItem btnBrowseFiles;
    private StringItem btnUploadNow;
    private StringItem btnFindByCode;
    private StringItem btnPublicFiles;
    private StringItem btnMyUploads;
    private StringItem settingsSummaryItem;
    private StringItem btnSettings;

    // Search by Code Form
    private Form findForm;
    private TextField findCodeField;
    private Command cmdDoSearch;

    // Consolidated Settings Form
    private Form settingsForm;
    private TextField optServerUrl;
    private ChoiceGroup optExpiry;
    private ChoiceGroup optBurn;
    private TextField optPassword;
    private TextField optSlug;
    private TextField optAccount;
    private ChoiceGroup optFastUpload;

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
    private Vector galleryPhotoItems = new Vector();

    // T9 Quick Filter State
    private String publicFilter = "";
    private String historyFilter = "";
    private Command cmdFilterPublic;
    private Command cmdClearFilterPublic;
    private Command cmdFilterHistory;
    private Command cmdClearFilterHistory;
    private Vector currentFilteredPublic = new Vector();

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
            checkVersionQuietly(false);
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
        cmdFindByCodeMenu = new Command("Find by Code", Command.SCREEN, 5);
        cmdPublicFiles = new Command("Public Files", Command.SCREEN, 6);
        cmdMyUploads = new Command("Upload History", Command.SCREEN, 7);
        cmdSettings = new Command("Settings", Command.SCREEN, 8);
        cmdTerms = new Command("Terms of Service", Command.SCREEN, 9);
        cmdPrivacy = new Command("Privacy Policy", Command.SCREEN, 10);
        cmdAbout = new Command("About", Command.SCREEN, 11);
        cmdExit = new Command("Exit", Command.SCREEN, 12);
        cmdExitSoftkey = new Command("Exit", Command.EXIT, 13);

        cmdBack = new Command("Back", Command.BACK, 1);
        cmdSelect = new Command("Select", Command.OK, 1);
        cmdSaveSettings = new Command("Save", Command.OK, 1);
        cmdDoSearch = new Command("Search", Command.OK, 1);
        cmdItemAction = new Command("Go", Command.ITEM, 1);

        cmdFilterPublic = new Command("Quick Filter", Command.SCREEN, 2);
        cmdClearFilterPublic = new Command("Clear Filter", Command.SCREEN, 3);
        cmdFilterHistory = new Command("Quick Filter", Command.SCREEN, 2);
        cmdClearFilterHistory = new Command("Clear Filter", Command.SCREEN, 3);
        cmdExportHistory = new Command("Export Links", Command.SCREEN, 4);
    }

    private void initMainForm() {
        mainForm = new Form("Nullhost Mobile");

        // 1. Upload Section
        fileStatusItem = new StringItem("Selected File:", "No file selected");
        fileStatusItem.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        mainForm.append(fileStatusItem);

        previewImageItem = new ImageItem(null, null, Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER, "Preview");
        mainForm.append(previewImageItem);

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

        btnCreatePaste = new StringItem(null, "Create Paste", Item.BUTTON);
        btnCreatePaste.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnCreatePaste.setDefaultCommand(cmdItemAction);
        btnCreatePaste.setItemCommandListener(this);
        mainForm.append(btnCreatePaste);

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

        // Softkey Commands
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
            if (previewImageItem != null) previewImageItem.setImage(null);
        } else {
            String fname = selectedFilePath.substring(selectedFilePath.lastIndexOf('/') + 1);
            String lower = fname.toLowerCase();
            boolean isImg = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".bmp");
            if (isImg) {
                int[] dims = readImageDimensions(selectedFilePath);
                if (dims != null && dims.length >= 2) {
                    fileStatusItem.setText(fname + " (" + dims[0] + "x" + dims[1] + ", " + formatSize(selectedFileSize) + ")");
                } else {
                    fileStatusItem.setText(fname + " (" + formatSize(selectedFileSize) + ")");
                }
                if (previewImageItem != null) {
                    Image prev = loadThumbnailSafe(selectedFilePath, 96);
                    previewImageItem.setImage(prev);
                }
            } else {
                fileStatusItem.setText(fname + " (" + formatSize(selectedFileSize) + ")");
                if (previewImageItem != null) previewImageItem.setImage(null);
            }
        }
        if (myUploads.isEmpty()) {
            btnMyUploads.setText("Upload History");
        } else {
            btnMyUploads.setText("Upload History: " + myUploads.size() + " items");
        }
        settingsSummaryItem.setText("Expiry " + EXPIRY_LABELS[expiryIndex] + ", Burn " + BURN_LABELS[burnIndex] + (fastUpload ? ", 1080p Fast" : ""));
    }

    public void commandAction(Command c, Item item) {
        if (item == btnSelectPhoto) {
            openPhotoGallery();
        } else if (item == btnBrowseFiles) {
            openFileBrowser(null);
        } else if (item == btnUploadNow) {
            startUploadThread();
        } else if (item == btnCreatePaste) {
            openCreatePasteForm();
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
                display.setCurrent(mainForm);
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
        } else if (c == cmdFilterPublic) {
            promptFilterPublic();
        } else if (c == cmdClearFilterPublic) {
            publicFilter = "";
            renderPublicFilesList();
        } else if (c == cmdFilterHistory) {
            promptFilterHistory();
        } else if (c == cmdClearFilterHistory) {
            historyFilter = "";
            renderUploadHistoryList();
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
            dos.writeBoolean(false);
            dos.writeBoolean(fastUpload);
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
                    if (dis.available() > 0) {
                        dis.readBoolean();
                    }
                    if (dis.available() > 0) {
                        fastUpload = dis.readBoolean();
                    }
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

        findForm.addCommand(cmdDoSearch);
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
    // IMAGE DIMENSIONS & THUMBNAILS (MEMORY-SAFE HEADER SCANNER)
    // ------------------------------------------------------------------------
    private int[] readImageDimensions(String fileUrl) {
        FileConnection fc = null;
        InputStream is = null;
        try {
            fc = (FileConnection) Connector.open(fileUrl, Connector.READ);
            is = fc.openInputStream();
            byte[] header = new byte[32];
            int r = is.read(header);
            if (r < 8) return null;

            // PNG check: 89 50 4E 47 0D 0A 1A 0A
            if ((header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
                byte[] ihdr = new byte[16];
                int ir = is.read(ihdr);
                if (ir >= 16) {
                    int w = ((ihdr[8] & 0xFF) << 24) | ((ihdr[9] & 0xFF) << 16) | ((ihdr[10] & 0xFF) << 8) | (ihdr[11] & 0xFF);
                    int h = ((ihdr[12] & 0xFF) << 24) | ((ihdr[13] & 0xFF) << 16) | ((ihdr[14] & 0xFF) << 8) | (ihdr[15] & 0xFF);
                    return new int[]{w, h};
                }
            }

            // GIF check: GIF87a or GIF89a
            if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F') {
                int w = (header[6] & 0xFF) | ((header[7] & 0xFF) << 8);
                int h = (header[8] & 0xFF) | ((header[9] & 0xFF) << 8);
                return new int[]{w, h};
            }

            // BMP check: BM
            if (header[0] == 'B' && header[1] == 'M') {
                if (r >= 26) {
                    int w = (header[18] & 0xFF) | ((header[19] & 0xFF) << 8) | ((header[20] & 0xFF) << 16) | ((header[21] & 0xFF) << 24);
                    int h = (header[22] & 0xFF) | ((header[23] & 0xFF) << 8) | ((header[24] & 0xFF) << 16) | ((header[25] & 0xFF) << 24);
                    return new int[]{w, Math.abs(h)};
                }
            }

            // JPEG check: FF D8
            if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8) {
                int b1 = header[2] & 0xFF;
                int b2 = header[3] & 0xFF;
                while (true) {
                    while (b1 != 0xFF) {
                        b1 = b2;
                        b2 = is.read();
                        if (b2 == -1) return null;
                    }
                    while (b2 == 0xFF) {
                        b2 = is.read();
                        if (b2 == -1) return null;
                    }
                    int marker = b2;
                    if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                        byte[] sof = new byte[7];
                        int sr = is.read(sof);
                        if (sr >= 7) {
                            int h = ((sof[3] & 0xFF) << 8) | (sof[4] & 0xFF);
                            int w = ((sof[5] & 0xFF) << 8) | (sof[6] & 0xFF);
                            return new int[]{w, h};
                        }
                        return null;
                    }
                    if (marker == 0xD9 || marker == 0xDA) {
                        return null;
                    }
                    int l1 = is.read();
                    int l2 = is.read();
                    if (l1 == -1 || l2 == -1) return null;
                    int segLen = ((l1 & 0xFF) << 8) | (l2 & 0xFF);
                    int skip = segLen - 2;
                    while (skip > 0) {
                        long sk = is.skip(skip);
                        if (sk <= 0) break;
                        skip -= (int) sk;
                    }
                    b1 = is.read();
                    b2 = is.read();
                    if (b1 == -1 || b2 == -1) return null;
                }
            }
        } catch (Exception ex) {
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (fc != null) try { fc.close(); } catch (Exception e) {}
        }
        return null;
    }

    private Image loadThumbnailSafe(String fileUrl, int maxSize) {
        FileConnection fc = null;
        InputStream is = null;
        try {
            fc = (FileConnection) Connector.open(fileUrl, Connector.READ);
            long fsize = fc.fileSize();
            if (fsize <= 0) return null;

            if (fsize <= 393216L) {
                is = fc.openInputStream();
                Image src = Image.createImage(is);
                is.close();
                is = null;
                if (src != null) {
                    return createScaledThumbnail(src, maxSize, maxSize);
                }
            }

            String lower = fileUrl.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                is = fc.openInputStream();
                byte[] head = new byte[65536];
                int totalRead = 0;
                while (totalRead < head.length) {
                    int r = is.read(head, totalRead, head.length - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }
                is.close();
                is = null;
                byte[] exifThumb = extractExifThumbnail(head, totalRead);
                if (exifThumb != null && exifThumb.length > 0) {
                    Image thumb = Image.createImage(exifThumb, 0, exifThumb.length);
                    if (thumb != null) {
                        return createScaledThumbnail(thumb, maxSize, maxSize);
                    }
                }
            }
        } catch (Exception ex) {
        } finally {
            if (is != null) try { is.close(); } catch (Exception e) {}
            if (fc != null) try { fc.close(); } catch (Exception e) {}
        }
        return null;
    }

    private byte[] extractExifThumbnail(byte[] data, int len) {
        if (len < 64) return null;
        if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) return null;
        int p = 2;
        while (p + 4 < len) {
            if ((data[p] & 0xFF) != 0xFF) break;
            int marker = data[p + 1] & 0xFF;
            int segLen = ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
            if (marker == 0xE1) {
                int exifStart = p + 4;
                if (exifStart + 6 <= len && data[exifStart] == 'E' && data[exifStart + 1] == 'x' && data[exifStart + 2] == 'i' && data[exifStart + 3] == 'f') {
                    int tiff = exifStart + 6;
                    for (int s = tiff; s + 4 < p + 2 + segLen && s + 4 < len; s++) {
                        if ((data[s] & 0xFF) == 0xFF && (data[s + 1] & 0xFF) == 0xD8) {
                            for (int e = s + 2; e + 1 < p + 2 + segLen && e + 1 < len; e++) {
                                if ((data[e] & 0xFF) == 0xFF && (data[e + 1] & 0xFF) == 0xD9) {
                                    int thumbSize = (e + 2) - s;
                                    if (thumbSize > 100 && thumbSize <= 65536) {
                                        byte[] res = new byte[thumbSize];
                                        System.arraycopy(data, s, res, 0, thumbSize);
                                        return res;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            p += 2 + segLen;
        }
        return null;
    }

    private Image createScaledThumbnail(Image src, int maxW, int maxH) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxW && h <= maxH) return src;
        int newW = maxW;
        int newH = (h * maxW) / w;
        if (newH > maxH) {
            newH = maxH;
            newW = (w * maxH) / h;
        }
        if (newW < 1) newW = 1;
        if (newH < 1) newH = 1;
        try {
            int[] srcLine = new int[w];
            int[] destPixels = new int[newW * newH];
            for (int y = 0; y < newH; y++) {
                int sy = (y * h) / newH;
                src.getRGB(srcLine, 0, w, 0, sy, w, 1);
                int rowOff = y * newW;
                for (int x = 0; x < newW; x++) {
                    int sx = (x * w) / newW;
                    destPixels[rowOff + x] = srcLine[sx];
                }
            }
            return Image.createRGBImage(destPixels, newW, newH, false);
        } catch (Exception ex) {
            return src;
        }
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
        galleryPhotoItems.removeAllElements();

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
                    galleryPhotoItems.addElement(pi);
                    galleryEntries.addElement(pi.url);
                }
            }
        } catch (Exception e) {}

        galleryList = new List("Choose Photo", List.IMPLICIT);
        galleryList.addCommand(cmdSelect);
        galleryList.addCommand(cmdBack);
        galleryList.setCommandListener(this);

        // First item is always manual folder navigation
        galleryList.append("Browse Storage Folders", null);

        for (int i = 0; i < galleryPhotoItems.size(); i++) {
            PhotoItem pi = (PhotoItem) galleryPhotoItems.elementAt(i);
            galleryList.append(pi.name, null);
        }

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
        if (idx == 0) {
            openFileBrowser(null);
            return;
        }

        int photoIdx = idx - 1;
        if (photoIdx < 0 || photoIdx >= galleryPhotoItems.size()) return;

        PhotoItem pi = (PhotoItem) galleryPhotoItems.elementAt(photoIdx);
        selectedFilePath = pi.url;
        try {
            FileConnection fc = (FileConnection) Connector.open(selectedFilePath, Connector.READ);
            selectedFileSize = fc.fileSize();
            fc.close();
        } catch (Exception e) {
            selectedFileSize = 0;
        }
        updateMainFormStatus();
        display.setCurrent(mainForm);
    }

    // ------------------------------------------------------------------------
    // FILE BROWSER (STORAGE NAVIGATION - 1-CLICK SELECT)
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
                    Vector subDirs = new Vector();
                    Vector files = new Vector();
                    while (list.hasMoreElements()) {
                        String name = (String) list.nextElement();
                        if (name.endsWith("/")) {
                            subDirs.addElement(name);
                        } else {
                            files.addElement(name);
                        }
                    }

                    // 1. Directories first
                    for (int i = 0; i < subDirs.size(); i++) {
                        String dname = (String) subDirs.elementAt(i);
                        currentEntries.addElement(dirUrl + dname);
                        browserList.append("/" + dname.substring(0, dname.length() - 1), null);
                    }

                    // 2. Files (1-click selection)
                    for (int i = 0; i < files.size(); i++) {
                        String fname = (String) files.elementAt(i);
                        String furl = dirUrl + fname;
                        currentEntries.addElement(furl);
                        browserList.append(fname, null);
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
            // Folders are strictly navigation
            openFileBrowser(selectedUrl);
        } else {
            // 1-Click file selection
            selectedFilePath = selectedUrl;
            try {
                FileConnection fc = (FileConnection) Connector.open(selectedFilePath, Connector.READ);
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
    // IN-APP DIRECT TEXT PASTE CREATOR (PASTE ONLY)
    // ------------------------------------------------------------------------
    private void openCreatePasteForm() {
        final Form pasteForm = new Form("Create Text Paste");
        final TextField pasteField = new TextField("Enter / Paste Text:", "", 4000, TextField.ANY);
        pasteForm.append(pasteField);

        pasteForm.append(new Spacer(10, 8));

        StringItem btnUpload = new StringItem(null, "Upload Paste Now", Item.BUTTON);
        btnUpload.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnUpload.setDefaultCommand(cmdItemAction);
        btnUpload.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                String text = pasteField.getString();
                if (text == null || text.trim().length() == 0) {
                    showAlert("Empty Paste", "Please enter or paste some text first.", AlertType.WARNING, pasteForm);
                    return;
                }
                startPasteUploadThread(text.trim());
            }
        });
        pasteForm.append(btnUpload);

        final Command cmdUploadSoftkey = new Command("Upload Paste", Command.OK, 1);
        final Command cmdCancel = new Command("Cancel", Command.BACK, 2);
        pasteForm.addCommand(cmdUploadSoftkey);
        pasteForm.addCommand(cmdCancel);

        pasteForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdUploadSoftkey) {
                    String text = pasteField.getString();
                    if (text == null || text.trim().length() == 0) {
                        showAlert("Empty Paste", "Please enter or paste some text first.", AlertType.WARNING, pasteForm);
                        return;
                    }
                    startPasteUploadThread(text.trim());
                } else if (c == cmdCancel) {
                    display.setCurrent(mainForm);
                }
            }
        });

        display.setCurrent(pasteForm);
    }

    private void startPasteUploadThread(final String text) {
        final Form progressForm = new Form("Uploading Paste");
        final StringItem statusText = new StringItem("Status:", "Uploading text paste to server...");
        final Gauge gauge = new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING);
        progressForm.append(statusText);
        progressForm.append(gauge);
        display.setCurrent(progressForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String uploadTarget = base + "/api/mobile/upload";

                    UploadRecord record = null;
                    int maxAttempts = 3;
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                            if (attempt > 1) {
                                final String msg = "Connection drop. Retry " + attempt + " of " + maxAttempts + "...";
                                display.callSerially(new Runnable() {
                                    public void run() { statusText.setText(msg); }
                                });
                                try { Thread.sleep(1200); } catch (Exception ignored) {}
                            }
                            record = performPasteRequest(uploadTarget, statusText, text);
                            if (record != null) break;
                        } catch (Exception ex) {
                            if (attempt == maxAttempts) {
                                if (uploadTarget.startsWith("https://")) {
                                    display.callSerially(new Runnable() {
                                        public void run() { statusText.setText("Retrying over HTTP fallback..."); }
                                    });
                                    String fallbackTarget = "http://" + uploadTarget.substring(8);
                                    record = performPasteRequest(fallbackTarget, statusText, text);
                                } else {
                                    throw ex;
                                }
                            }
                        }
                    }

                    if (record != null) {
                        if (record.qrUrl != null && record.qrUrl.length() > 0) {
                            display.callSerially(new Runnable() {
                                public void run() { statusText.setText("Generating QR code image..."); }
                            });
                            try {
                                HttpConnection qrConn = (HttpConnection) Connector.open(record.qrUrl, Connector.READ);
                                qrConn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                                if (qrConn.getResponseCode() == 200) {
                                    InputStream qris = qrConn.openInputStream();
                                    record.qrImage = Image.createImage(qris);
                                    qris.close();
                                }
                                qrConn.close();
                            } catch (Exception ignored) {}
                        }
                        final UploadRecord finalRecord = record;
                        display.callSerially(new Runnable() {
                            public void run() {
                                showSuccessScreen(finalRecord);
                            }
                        });
                    } else {
                        showAlert("Upload Failed", "Server did not return a valid response.", AlertType.ERROR, mainForm);
                    }
                } catch (final Throwable ex) {
                    showAlert("Upload Error", "Failed to upload paste: " + (ex.getMessage() != null ? ex.getMessage() : ex.toString()), AlertType.ERROR, mainForm);
                }
            }
        });
        t.start();
    }

    private void setStatusSafe(final StringItem item, final String text) {
        if (item == null) return;
        if (display != null) {
            display.callSerially(new Runnable() {
                public void run() {
                    try {
                        item.setText(text);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private UploadRecord performPasteRequest(String targetUrl, StringItem statusText, String text) throws Exception {
        String expiry = EXPIRY_VALS[expiryIndex];
        String burn = BURN_VALS[burnIndex];

        String boundary = "----NullhostBoundary" + System.currentTimeMillis();
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

        setStatusSafe(statusText, "Sending paste data...");
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
        writeField(os, boundary, "paste_content", text);

        os.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();

        setStatusSafe(statusText, "Waiting for server response...");
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
            record.type = "paste";
            record.filename = "paste.txt";
            record.content = text;
            record.size = text.getBytes("UTF-8").length;

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
            savePersistentSettings();
            return record;
        }

        return null;
    }

    private String saveLocalTextFile(String fname, String content) throws Exception {
        String[] roots = {"file:///E:/Data/", "file:///E:/", "file:///C:/Data/", "file:///C:/"};
        String targetUrl = null;

        for (int i = 0; i < roots.length; i++) {
            try {
                FileConnection fc = (FileConnection) Connector.open(roots[i], Connector.READ);
                if (fc.exists() && fc.isDirectory()) {
                    fc.close();
                    targetUrl = roots[i] + fname;
                    break;
                }
                fc.close();
            } catch (Exception ignored) {}
        }

        if (targetUrl == null) {
            Enumeration en = FileSystemRegistry.listRoots();
            if (en.hasMoreElements()) {
                String r = (String) en.nextElement();
                String rUrl = "file:///" + r;
                if (!rUrl.endsWith("/")) rUrl += "/";
                targetUrl = rUrl + fname;
            } else {
                throw new IOException("No storage drives accessible");
            }
        }

        FileConnection outFc = (FileConnection) Connector.open(targetUrl, Connector.READ_WRITE);
        if (outFc.exists()) {
            outFc.delete();
        }
        outFc.create();
        OutputStream os = outFc.openOutputStream();
        os.write(content.getBytes("UTF-8"));
        os.flush();
        os.close();
        outFc.close();
        return targetUrl;
    }

    // ------------------------------------------------------------------------
    // EXPORT UPLOAD HISTORY TO LOCAL FILE & NOTES
    // ------------------------------------------------------------------------
    private void exportUploadHistory(Displayable backTarget) {
        if (myUploads == null || myUploads.isEmpty()) {
            showAlert("No Uploads", "There are no upload links in history to export.", AlertType.INFO, backTarget);
            return;
        }

        StringBuffer sb = new StringBuffer();
        sb.append("=== Nullhost Upload Links ===\n");
        sb.append("Total Items: ").append(myUploads.size()).append("\n\n");

        for (int i = 0; i < myUploads.size(); i++) {
            UploadRecord rec = (UploadRecord) myUploads.elementAt(i);
            sb.append((i + 1)).append(". ").append(rec.filename).append(" (").append(formatSize(rec.size)).append(")\n");
            sb.append("   Link: ").append(rec.url).append("\n");
            if (rec.rawUrl != null && !rec.rawUrl.equals(rec.url)) {
                sb.append("   Direct: ").append(rec.rawUrl).append("\n");
            }
            if (rec.deleteUrl != null && rec.deleteUrl.length() > 0) {
                sb.append("   Delete: ").append(rec.deleteUrl).append("\n");
            }
            sb.append("\n");
        }

        String exportText = sb.toString();
        String savedLoc = null;
        try {
            savedLoc = saveLocalTextFile("Nullhost_Links.txt", exportText);
        } catch (Exception ex) {}

        String title = savedLoc != null ? "Saved: " + savedLoc.substring(savedLoc.lastIndexOf('/') + 1) : "Exported Links";
        showCustomTextViewer(title, exportText, backTarget);
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

                    renderPublicFilesList();
                } catch (Exception e) {
                    showAlert("Public Files", "Could not fetch files: " + e.getMessage(), AlertType.ERROR, mainForm);
                }
            }
        });
        t.start();
    }

    private void renderPublicFilesList() {
        if (publicItems == null || publicItems.isEmpty()) {
            showAlert("Public Files", "No active public files currently hosted.", AlertType.INFO, mainForm);
            return;
        }

        currentFilteredPublic.removeAllElements();
        String filterClean = publicFilter != null ? publicFilter.trim().toLowerCase() : "";
        for (int i = 0; i < publicItems.size(); i++) {
            UploadRecord rec = (UploadRecord) publicItems.elementAt(i);
            if (filterClean.length() == 0 || (rec.filename != null && rec.filename.toLowerCase().indexOf(filterClean) != -1)) {
                currentFilteredPublic.addElement(rec);
            }
        }

        String title = "Public: " + currentFilteredPublic.size() + "/" + publicItems.size();
        if (filterClean.length() > 0) {
            title = "Filter: \"" + publicFilter + "\" (" + currentFilteredPublic.size() + ")";
        }

        publicList = new List(title, List.IMPLICIT);
        publicList.addCommand(cmdSelect);
        publicList.addCommand(cmdFilterPublic);
        if (filterClean.length() > 0) {
            publicList.addCommand(cmdClearFilterPublic);
        }
        publicList.addCommand(cmdBack);
        publicList.setCommandListener(NullhostMIDlet.this);

        for (int i = 0; i < currentFilteredPublic.size(); i++) {
            UploadRecord rec = (UploadRecord) currentFilteredPublic.elementAt(i);
            publicList.append(rec.filename + " - " + formatSize(rec.size), null);
        }

        display.setCurrent(publicList);
    }

    private void promptFilterPublic() {
        final TextBox tb = new TextBox("Quick Filter", publicFilter, 32, TextField.ANY);
        final Command cmdOk = new Command("Filter", Command.OK, 1);
        final Command cmdCancel = new Command("Cancel", Command.BACK, 2);
        tb.addCommand(cmdOk);
        tb.addCommand(cmdCancel);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdOk) {
                    publicFilter = tb.getString().trim();
                    renderPublicFilesList();
                } else if (c == cmdCancel) {
                    renderPublicFilesList();
                }
            }
        });
        display.setCurrent(tb);
    }

    private void handlePublicSelect() {
        int idx = publicList.getSelectedIndex();
        if (idx < 0 || idx >= currentFilteredPublic.size()) return;
        UploadRecord rec = (UploadRecord) currentFilteredPublic.elementAt(idx);
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

        final Vector filtered = new Vector();
        String filterClean = historyFilter != null ? historyFilter.trim().toLowerCase() : "";
        for (int i = 0; i < myUploads.size(); i++) {
            UploadRecord rec = (UploadRecord) myUploads.elementAt(i);
            if (filterClean.length() == 0 || (rec.filename != null && rec.filename.toLowerCase().indexOf(filterClean) != -1)) {
                filtered.addElement(rec);
            }
        }

        String title = "History: " + filtered.size() + "/" + myUploads.size();
        if (filterClean.length() > 0) {
            title = "Filter: \"" + historyFilter + "\" (" + filtered.size() + ")";
        }

        final List histList = new List(title, List.IMPLICIT);
        final Command cmdHistSelect = new Command("View", Command.OK, 1);
        final Command cmdHistBack = new Command("Back", Command.BACK, 2);
        histList.addCommand(cmdHistSelect);
        histList.addCommand(cmdFilterHistory);
        if (filterClean.length() > 0) {
            histList.addCommand(cmdClearFilterHistory);
        }
        histList.addCommand(cmdExportHistory);
        histList.addCommand(cmdHistBack);

        for (int i = 0; i < filtered.size(); i++) {
            UploadRecord rec = (UploadRecord) filtered.elementAt(i);
            String itemTitle = (rec.hasPassword ? "[Locked] " : "") + rec.filename + " - " + formatSize(rec.size);
            histList.append(itemTitle, null);
        }

        histList.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdHistSelect || c == List.SELECT_COMMAND) {
                    int idx = histList.getSelectedIndex();
                    if (idx >= 0 && idx < filtered.size()) {
                        UploadRecord rec = (UploadRecord) filtered.elementAt(idx);
                        if (rec.hasPassword && (rec.password == null || rec.password.length() == 0)) {
                            promptFilePassword(rec, rec.id, histList, true);
                        } else {
                            showItemDetails(rec, histList, true);
                        }
                    }
                } else if (c == cmdFilterHistory) {
                    promptFilterHistory();
                } else if (c == cmdClearFilterHistory) {
                    historyFilter = "";
                    renderUploadHistoryList();
                } else if (c == cmdExportHistory) {
                    exportUploadHistory(histList);
                } else if (c == cmdHistBack) {
                    display.setCurrent(mainForm);
                }
            }
        });

        display.setCurrent(histList);
    }

    private void promptFilterHistory() {
        final TextBox tb = new TextBox("Quick Filter", historyFilter, 32, TextField.ANY);
        final Command cmdOk = new Command("Filter", Command.OK, 1);
        final Command cmdCancel = new Command("Cancel", Command.BACK, 2);
        tb.addCommand(cmdOk);
        tb.addCommand(cmdCancel);
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdOk) {
                    historyFilter = tb.getString().trim();
                    renderUploadHistoryList();
                } else if (c == cmdCancel) {
                    renderUploadHistoryList();
                }
            }
        });
        display.setCurrent(tb);
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

        // 1. Image Viewer Action
        if (isImageItem(rec)) {
            StringItem btnViewImg = new StringItem(null, "View Image", Item.BUTTON);
            btnViewImg.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
            btnViewImg.setDefaultCommand(cmdItemAction);
            btnViewImg.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                    openImageContent(rec, f);
                }
            });
            f.append(btnViewImg);
        }

        // 2. Text Actions (if text file or paste)
        if (isTextItem(rec)) {
            StringItem btnViewText = new StringItem(null, "Read Text File", Item.BUTTON);
            btnViewText.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
            btnViewText.setDefaultCommand(cmdItemAction);
            btnViewText.setItemCommandListener(new ItemCommandListener() {
                public void commandAction(Command c, Item item) {
                    openTextContent(rec, f);
                }
            });
            f.append(btnViewText);
        }

        // 3. Prominent Download Button (triggers native Symbian download manager)
        StringItem btnDownload = new StringItem(null, "Download File", Item.BUTTON);
        btnDownload.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnDownload.setDefaultCommand(cmdItemAction);
        btnDownload.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                triggerFileDownload(rec, f);
            }
        });
        f.append(btnDownload);

        // 4. Open in Browser Button
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

        // 5. Delete Button if viewing an item from My Uploads
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
        final Command cmdViewImgMenu = isImageItem(rec) ? new Command("View Image", Command.SCREEN, 2) : null;
        final Command cmdViewTextMenu = isTextItem(rec) ? new Command("Read Text", Command.SCREEN, 3) : null;
        final Command cmdDownloadMenu = new Command("Download File", Command.SCREEN, 4);
        final Command cmdOpenMenu = new Command("Open in Browser", Command.SCREEN, 5);
        final Command cmdDelMenu = isMyUpload ? new Command("Delete File", Command.SCREEN, 6) : null;

        f.addCommand(cmdDetailsBack);
        if (cmdViewImgMenu != null) f.addCommand(cmdViewImgMenu);
        if (cmdViewTextMenu != null) f.addCommand(cmdViewTextMenu);
        f.addCommand(cmdDownloadMenu);
        f.addCommand(cmdOpenMenu);
        if (cmdDelMenu != null) f.addCommand(cmdDelMenu);

        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdDetailsBack) {
                    display.setCurrent(backTarget);
                } else if (c == cmdViewImgMenu) {
                    openImageContent(rec, f);
                } else if (c == cmdViewTextMenu) {
                    openTextContent(rec, f);
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

    // ------------------------------------------------------------------------
    // IN-APP TEXT VIEWER (CLEAN READ-ONLY, NO BROWSER REDIRECTS, RELIABLE BACK)
    // ------------------------------------------------------------------------
    private void openTextContent(final UploadRecord rec, final Displayable backTarget) {
        if (rec == null) return;
        if (rec.content != null && rec.content.trim().length() > 0 && !"null".equalsIgnoreCase(rec.content.trim()) && !"file".equals(rec.type)) {
            showCustomTextViewer(rec, rec.content, backTarget);
            return;
        }

        final Form loadForm = new Form("Loading Text");
        loadForm.append(new StringItem("Status:", "Fetching text content..."));
        loadForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));

        final Command cmdCancel = new Command("Back", Command.BACK, 1);
        loadForm.addCommand(cmdCancel);
        loadForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdCancel) {
                    display.setCurrent(backTarget != null ? backTarget : mainForm);
                }
            }
        });
        display.setCurrent(loadForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String fetchUrl = (rec.rawUrl != null && rec.rawUrl.length() > 0) ? rec.rawUrl : rec.downloadUrl;
                    if (fetchUrl == null || fetchUrl.length() == 0) {
                        display.callSerially(new Runnable() {
                            public void run() {
                                showAlert("Error", "No text URL available.", AlertType.ERROR, backTarget != null ? backTarget : mainForm);
                            }
                        });
                        return;
                    }

                    HttpConnection conn = null;
                    try {
                        conn = (HttpConnection) Connector.open(fetchUrl, Connector.READ);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/" + APP_VERSION);
                        int respCode = conn.getResponseCode();
                        if (respCode != 200) {
                            conn.close();
                            final int code = respCode;
                            display.callSerially(new Runnable() {
                                public void run() {
                                    showAlert("Error", "Server returned HTTP " + code, AlertType.ERROR, backTarget != null ? backTarget : mainForm);
                                }
                            });
                            return;
                        }
                    } catch (Exception ex) {
                        if (fetchUrl.startsWith("https://")) {
                            String httpUrl = "http://" + fetchUrl.substring(8);
                            conn = (HttpConnection) Connector.open(httpUrl, Connector.READ);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/" + APP_VERSION);
                            int respCode = conn.getResponseCode();
                            if (respCode != 200) {
                                conn.close();
                                final int code = respCode;
                                display.callSerially(new Runnable() {
                                    public void run() {
                                        showAlert("Error", "Server returned HTTP " + code, AlertType.ERROR, backTarget != null ? backTarget : mainForm);
                                    }
                                });
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
                        if (total > 65536) break; // 64 KB safety limit
                    }
                    is.close();
                    conn.close();

                    final String text = new String(baos.toByteArray(), "UTF-8");
                    rec.content = text;
                    display.callSerially(new Runnable() {
                        public void run() {
                            showCustomTextViewer(rec, text, backTarget);
                        }
                    });
                } catch (final Throwable e) {
                    display.callSerially(new Runnable() {
                        public void run() {
                            showAlert("Error", "Could not load text: " + (e.getMessage() != null ? e.getMessage() : e.toString()), AlertType.ERROR, backTarget != null ? backTarget : mainForm);
                        }
                    });
                }
            }
        });
        t.start();
    }

    private void showCustomTextViewer(final UploadRecord rec, final String text, final Displayable backTarget) {
        final Form viewForm = new Form(rec.filename != null ? rec.filename : "Text Viewer");

        StringItem textItem = new StringItem(null, text != null ? text : "");
        textItem.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        viewForm.append(textItem);

        final Command cmdViewerBack = new Command("Back", Command.BACK, 1);
        viewForm.addCommand(cmdViewerBack);

        viewForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdViewerBack) {
                    display.setCurrent(backTarget != null ? backTarget : mainForm);
                }
            }
        });

        display.setCurrent(viewForm);
    }

    private void showCustomTextViewer(final String title, final String text, final Displayable backTarget) {
        final Form viewForm = new Form(title != null ? title : "Text Viewer");

        StringItem textItem = new StringItem(null, text != null ? text : "");
        textItem.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        viewForm.append(textItem);

        final Command cmdDone = new Command("Back", Command.BACK, 1);
        viewForm.addCommand(cmdDone);

        viewForm.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdDone) {
                    display.setCurrent(backTarget != null ? backTarget : mainForm);
                }
            }
        });

        display.setCurrent(viewForm);
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

    private static boolean isImageItem(UploadRecord rec) {
        if (rec == null) return false;
        if (rec.type != null && rec.type.equalsIgnoreCase("image")) return true;
        String fn = rec.filename != null ? rec.filename.toLowerCase() : "";
        return fn.endsWith(".jpg") || fn.endsWith(".jpeg") || fn.endsWith(".png") ||
               fn.endsWith(".gif") || fn.endsWith(".bmp") || fn.endsWith(".webp");
    }

    private void openImageContent(final UploadRecord rec, final Displayable backTarget) {
        final Form loadingForm = new Form("Loading Image");
        loadingForm.append(new StringItem("Status:", "Fetching image from server..."));
        loadingForm.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        display.setCurrent(loadingForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String imgUrl = base + "/api/mobile/preview/" + rec.id;
                    if (rec.password != null && rec.password.length() > 0) {
                        imgUrl += "?password=" + urlEncode(rec.password);
                    }

                    HttpConnection conn = null;
                    InputStream is = null;
                    Image img = null;
                    try {
                        conn = (HttpConnection) Connector.open(imgUrl, Connector.READ);
                        conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                        if (conn.getResponseCode() == 200) {
                            is = conn.openInputStream();
                            img = Image.createImage(is);
                            is.close();
                            is = null;
                        }
                        conn.close();
                        conn = null;
                    } catch (Exception ex) {
                        if (conn != null) try { conn.close(); } catch (Exception ignored) {}
                        if (is != null) try { is.close(); } catch (Exception ignored) {}
                        String rawTarget = (rec.rawUrl != null && rec.rawUrl.length() > 0) ? rec.rawUrl : rec.url;
                        if (rawTarget != null && rawTarget.length() > 0) {
                            conn = (HttpConnection) Connector.open(rawTarget, Connector.READ);
                            conn.setRequestProperty("User-Agent", "Nullhost-J2ME/1.0");
                            if (conn.getResponseCode() == 200) {
                                is = conn.openInputStream();
                                img = Image.createImage(is);
                                is.close();
                                is = null;
                            }
                            conn.close();
                            conn = null;
                        }
                    }

                    if (img != null) {
                        showImageViewer(rec, img, backTarget);
                    } else {
                        showAlert("Image Viewer", "Could not decode image from server.", AlertType.ERROR, backTarget);
                    }
                } catch (Exception e) {
                    showAlert("Image Viewer", "Error loading image: " + e.getMessage(), AlertType.ERROR, backTarget);
                }
            }
        });
        t.start();
    }

    private void showImageViewer(final UploadRecord rec, Image img, final Displayable backTarget) {
        final Form vf = new Form(rec.filename);
        ImageItem ii = new ImageItem(null, img, Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER, rec.filename);
        vf.append(ii);
        vf.append(new StringItem("Resolution:", img.getWidth() + " x " + img.getHeight() + " px"));
        vf.append(new StringItem("Size:", formatSize(rec.size)));

        StringItem btnOpen = new StringItem(null, "Open in Browser", Item.BUTTON);
        btnOpen.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnOpen.setDefaultCommand(cmdItemAction);
        btnOpen.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                if (rec.url != null && rec.url.length() > 0) {
                    try { platformRequest(rec.url); } catch (Exception ex) {}
                }
            }
        });
        vf.append(btnOpen);

        final Command cmdViewBack = new Command("Back", Command.BACK, 1);
        final Command cmdViewBrowser = new Command("Open in Browser", Command.SCREEN, 2);
        vf.addCommand(cmdViewBack);
        vf.addCommand(cmdViewBrowser);

        vf.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdViewBack) {
                    display.setCurrent(backTarget);
                } else if (c == cmdViewBrowser) {
                    if (rec.url != null && rec.url.length() > 0) {
                        try { platformRequest(rec.url); } catch (Exception ex) {}
                    }
                }
            }
        });

        display.setCurrent(vf);
    }

    // ------------------------------------------------------------------------
    // SERVER FILE DELETION
    // ------------------------------------------------------------------------
    private void deleteUploadedItem(final UploadRecord rec) {
        if (rec == null) return;
        final Form delForm = new Form("Deleting File");
        delForm.append(new StringItem("Status:", "Deleting " + (rec.filename != null ? rec.filename : "item") + " from server..."));
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

                    final int code = conn.getResponseCode();
                    conn.close();

                    display.callSerially(new Runnable() {
                        public void run() {
                            try {
                                if (code == 200) {
                                    myUploads.removeElement(rec);
                                    updateMainFormStatus();
                                    showAlert("File Deleted", (rec.filename != null ? rec.filename : "Item") + " was deleted from server.", AlertType.CONFIRMATION, mainForm);
                                } else {
                                    showAlert("Delete Failed", "Server returned HTTP " + code, AlertType.ERROR, mainForm);
                                }
                            } catch (Throwable t) {
                                display.setCurrent(mainForm);
                            }
                        }
                    });
                } catch (final Throwable ex) {
                    display.callSerially(new Runnable() {
                        public void run() {
                            showAlert("Delete Error", "Could not delete: " + (ex.getMessage() != null ? ex.getMessage() : ex.toString()), AlertType.ERROR, mainForm);
                        }
                    });
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
        final Gauge gauge = new Gauge(null, false, 100, 0);
        progressForm.append(statusText);
        progressForm.append(gauge);
        display.setCurrent(progressForm);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    UploadRecord record = executeUpload(statusText, gauge, selectedFilePath);
                    if (record != null) {
                        final UploadRecord finalRec = record;
                        display.callSerially(new Runnable() {
                            public void run() {
                                showSuccessScreen(finalRec);
                            }
                        });
                    } else {
                        showAlert("Upload Failed", "Server did not return a valid download link.", AlertType.ERROR, mainForm);
                    }
                } catch (final Throwable e) {
                    showAlert("Upload Error", (e.getMessage() != null) ? e.getMessage() : e.toString(), AlertType.ERROR, mainForm);
                }
            }
        });
        t.start();
    }

    private UploadRecord executeUpload(StringItem statusText, Gauge gauge, String uploadFilePath) throws Exception {
        String base = serverUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String uploadTarget = base + "/api/mobile/upload";

        UploadRecord record = null;
        int maxAttempts = 3; // 1 initial + 2 retries on network drops
        Exception lastEx = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (attempt > 1) {
                    setStatusSafe(statusText, "Connection drop. Retry " + attempt + " of " + maxAttempts + "...");
                    try { Thread.sleep(1200); } catch (Exception ignored) {}
                }
                record = performUploadRequest(uploadTarget, statusText, gauge, uploadFilePath);
                if (record != null) break;
            } catch (Exception ex) {
                lastEx = ex;
                if (attempt == maxAttempts) {
                    if (uploadTarget.startsWith("https://")) {
                        setStatusSafe(statusText, "Retrying over HTTP fallback...");
                        String fallbackTarget = "http://" + uploadTarget.substring(8);
                        record = performUploadRequest(fallbackTarget, statusText, gauge, uploadFilePath);
                    } else {
                        throw ex;
                    }
                }
            }
        }

        // Fetch Live QR Code image
        if (record != null && record.qrUrl != null && record.qrUrl.length() > 0) {
            setStatusSafe(statusText, "Generating QR code image...");
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

    private UploadRecord performUploadRequest(String targetUrl, StringItem statusText, Gauge gauge, String uploadFilePath) throws Exception {
        String expiry = EXPIRY_VALS[expiryIndex];
        String burn = BURN_VALS[burnIndex];

        String boundary = "----NullhostBoundary" + System.currentTimeMillis();
        String fname = uploadFilePath.substring(uploadFilePath.lastIndexOf('/') + 1);

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

        if (gauge != null) {
            gauge.setMaxValue(100);
            gauge.setValue(0);
        }
        setStatusSafe(statusText, "Preparing upload data...");
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
        if (fastUpload) {
            writeField(os, boundary, "fast_resize", "1");
        }

        os.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fname + "\"\r\n").getBytes("UTF-8"));
        os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));

        FileConnection fc = (FileConnection) Connector.open(uploadFilePath, Connector.READ);
        long totalBytes = fc.fileSize();
        InputStream fis = fc.openInputStream();
        byte[] fbuf = new byte[2048];
        int flen;
        long sent = 0;
        long startTime = System.currentTimeMillis();
        long lastUpdate = 0;

        while ((flen = fis.read(fbuf)) != -1) {
            os.write(fbuf, 0, flen);
            sent += flen;
            long now = System.currentTimeMillis();
            if (now - lastUpdate >= 250 || sent == totalBytes) {
                lastUpdate = now;
                int pct = totalBytes > 0 ? (int)((sent * 100L) / totalBytes) : 0;
                if (pct > 100) pct = 100;
                if (gauge != null) gauge.setValue(pct);
                long elapsed = now - startTime;
                long kbps = elapsed > 200 ? ((sent * 1000L / elapsed) / 1024L) : 0;
                long remBytes = totalBytes - sent;
                long etaSec = (kbps > 0 && remBytes > 0) ? (remBytes / (kbps * 1024L)) : 0;
                setStatusSafe(statusText, pct + "% (" + formatSize(sent) + " / " + formatSize(totalBytes) + ") - " + kbps + " KB/s - " + etaSec + "s left");
            }
        }
        fis.close();
        fc.close();

        os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        os.flush();
        os.close();

        setStatusSafe(statusText, "Waiting for server response...");
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
                record.size = totalBytes;
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
            savePersistentSettings();
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
        if (record == null) {
            display.setCurrent(mainForm);
            return;
        }

        try {
            final Form successForm = new Form("Upload Successful");

            if (record.qrImage != null) {
                try {
                    successForm.append(new ImageItem(null, record.qrImage, Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_BEFORE | Item.LAYOUT_NEWLINE_AFTER, "Scan to Download"));
                } catch (Throwable ignored) {}
            }

            if (record.id != null) {
                successForm.append(new StringItem("Item Code:", record.id));
            }
            successForm.append(new StringItem("File Name:", record.filename != null ? record.filename : "paste.txt"));
            successForm.append(new StringItem("File Size:", formatSize(record.size)));
            if (record.expiry != null) {
                successForm.append(new StringItem("Retention:", record.expiry));
            }
            if (record.burn != null) {
                successForm.append(new StringItem("Burn Limit:", record.burn));
            }
            if (record.url != null) {
                successForm.append(new StringItem("Short URL:", record.url));
            }
            if (record.rawUrl != null) {
                successForm.append(new StringItem("Direct Raw Link:", record.rawUrl));
            }

            successForm.append(new Spacer(10, 6));

            // In-form action buttons (NO Command.OK -> NO checkmark button on Belle toolbar!)
            if (isImageItem(record)) {
                StringItem btnViewImg = new StringItem(null, "View Image", Item.BUTTON);
                btnViewImg.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
                btnViewImg.setDefaultCommand(cmdItemAction);
                btnViewImg.setItemCommandListener(new ItemCommandListener() {
                    public void commandAction(Command c, Item item) {
                        openImageContent(record, successForm);
                    }
                });
                successForm.append(btnViewImg);
            }

            if (isTextItem(record)) {
                StringItem btnViewText = new StringItem(null, "Read Text File", Item.BUTTON);
                btnViewText.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
                btnViewText.setDefaultCommand(cmdItemAction);
                btnViewText.setItemCommandListener(new ItemCommandListener() {
                    public void commandAction(Command c, Item item) {
                        openTextContent(record, successForm);
                    }
                });
                successForm.append(btnViewText);
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
                        } catch (Throwable ex) {
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
            final Command cmdViewImgMenu = isImageItem(record) ? new Command("View Image", Command.SCREEN, 2) : null;
            final Command cmdViewTextMenu = isTextItem(record) ? new Command("Read Text", Command.SCREEN, 2) : null;
            final Command cmdNew = new Command("Upload Another", Command.SCREEN, 3);
            final Command cmdOpen = new Command("Open in Browser", Command.SCREEN, 4);
            final Command cmdDel = new Command("Delete File", Command.SCREEN, 5);
            final Command cmdExitApp = new Command("Exit", Command.EXIT, 6);

            successForm.addCommand(cmdDone);
            if (cmdViewImgMenu != null) successForm.addCommand(cmdViewImgMenu);
            if (cmdViewTextMenu != null) successForm.addCommand(cmdViewTextMenu);
            successForm.addCommand(cmdNew);
            successForm.addCommand(cmdOpen);
            successForm.addCommand(cmdDel);
            successForm.addCommand(cmdExitApp);

            successForm.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    try {
                        if (c == cmdDone) {
                            updateMainFormStatus();
                            display.setCurrent(mainForm);
                        } else if (c == cmdViewImgMenu) {
                            openImageContent(record, successForm);
                        } else if (c == cmdViewTextMenu) {
                            openTextContent(record, successForm);
                        } else if (c == cmdNew) {
                            selectedFilePath = null;
                            selectedFileSize = 0;
                            updateMainFormStatus();
                            display.setCurrent(mainForm);
                        } else if (c == cmdOpen) {
                            try {
                                platformRequest(record.url);
                            } catch (Throwable ex) {
                                showAlert("Browser Error", "Could not open browser: " + ex.getMessage(), AlertType.ERROR, successForm);
                            }
                        } else if (c == cmdDel) {
                            deleteUploadedItem(record);
                        } else if (c == cmdExitApp) {
                            destroyApp(true);
                            notifyDestroyed();
                        }
                    } catch (Throwable t) {
                        display.setCurrent(mainForm);
                    }
                }
            });

            display.setCurrent(successForm);
        } catch (Throwable t) {
            showAlert("Success", "Upload successful: " + (record.url != null ? record.url : ""), AlertType.INFO, mainForm);
        }
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

        optFastUpload = new ChoiceGroup("Photo Quality:", Choice.EXCLUSIVE, new String[]{"Original (Full Quality)", "Fast Mobile (1080p Optimized)"}, null);
        optFastUpload.setSelectedIndex(fastUpload ? 1 : 0, true);
        settingsForm.append(optFastUpload);

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
        fastUpload = optFastUpload.getSelectedIndex() == 1;

        savePersistentSettings();

        updateMainFormStatus();
        showAlert("Settings Saved", "Settings and Account ID saved successfully.", AlertType.CONFIRMATION, mainForm);
    }

    private void showAbout() {
        long totalMem = Runtime.getRuntime().totalMemory() / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024;

        final Form f = new Form("About Nullhost");
        f.append(new StringItem("Nullhost Universal J2ME", "Version " + APP_VERSION + "\n\n" +
            "Fast, anonymous file and photo hosting for Symbian and vintage phones.\n\n" +
            "Features:\n" +
            "- Byte-accurate progress with speed & ETA\n" +
            "- In-app direct text paste creator\n" +
            "- Clean in-app text viewer\n" +
            "- In-app image viewer for hosted photos\n" +
            "- Instant photo dimensions & preview\n" +
            "- Automatic lightweight update checker (<0.2 KB)\n" +
            "- Export upload history to local text & notes\n" +
            "- T9 quick filter for public files & history\n" +
            "- Auto-retry resilience on network drops\n" +
            "- Direct Symbian download manager\n" +
            "- Persistent RMS settings & Account ID\n" +
            "- Live centered QR code generation\n\n" +
            "Server: " + serverUrl + "\n" +
            "Free Memory: " + freeMem + " KB / " + totalMem + " KB\n" +
            "Platform: " + System.getProperty("microedition.platform") + "\n\n" +
            "Gelaxiz (c) 2026\n"));

        StringItem btnCheckUpdate = new StringItem(null, "Check for Updates", Item.BUTTON);
        btnCheckUpdate.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnCheckUpdate.setDefaultCommand(cmdItemAction);
        btnCheckUpdate.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                checkVersionQuietly(true);
            }
        });
        f.append(btnCheckUpdate);

        final Command cmdCheckUpdateMenu = new Command("Check for Updates", Command.SCREEN, 2);
        f.addCommand(cmdBack);
        f.addCommand(cmdCheckUpdateMenu);
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdBack) {
                    display.setCurrent(mainForm);
                } else if (c == cmdCheckUpdateMenu) {
                    checkVersionQuietly(true);
                }
            }
        });
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
    // LIGHTWEIGHT AUTO-UPDATE CHECK (< 0.2 KB DATA)
    // ------------------------------------------------------------------------
    private void checkVersionQuietly(final boolean userRequested) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                HttpConnection hc = null;
                InputStream is = null;
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String url = base + "/api/version";
                    hc = (HttpConnection) Connector.open(url);
                    hc.setRequestMethod(HttpConnection.GET);
                    hc.setRequestProperty("User-Agent", "Nullhost-J2ME/" + APP_VERSION);
                    hc.setRequestProperty("Connection", "close");

                    int code = hc.getResponseCode();
                    if (code == HttpConnection.HTTP_OK) {
                        is = hc.openInputStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[256];
                        int read;
                        while ((read = is.read(buf)) != -1) {
                            baos.write(buf, 0, read);
                        }
                        String json = new String(baos.toByteArray(), "UTF-8");
                        final String latestJ2me = extractJsonField(json, "j2me");
                        final String j2meUrl = extractJsonField(json, "j2me_url");

                        if (latestJ2me != null && isNewerVersion(latestJ2me, APP_VERSION)) {
                            display.callSerially(new Runnable() {
                                public void run() {
                                    promptUpdateAvailable(latestJ2me, j2meUrl);
                                }
                            });
                            return;
                        }
                    }

                    if (userRequested) {
                        display.callSerially(new Runnable() {
                            public void run() {
                                showAlert("Up to Date", "You are using the latest version of Nullhost (v" + APP_VERSION + ").", AlertType.INFO, mainForm);
                            }
                        });
                    }
                } catch (final Exception e) {
                    if (userRequested) {
                        display.callSerially(new Runnable() {
                            public void run() {
                                showAlert("Update Check", "Could not check for updates: " + e.getMessage(), AlertType.WARNING, mainForm);
                            }
                        });
                    }
                } finally {
                    try { if (is != null) is.close(); } catch (Exception ex) {}
                    try { if (hc != null) hc.close(); } catch (Exception ex) {}
                }
            }
        });
        t.start();
    }

    private void promptUpdateAvailable(final String newVer, final String downloadUrl) {
        final Form uf = new Form("Update Available");
        uf.append(new StringItem("New Version Available!",
            "A newer version of Nullhost (v" + newVer + ") is available.\n\n" +
            "Your current version: v" + APP_VERSION + "\n\n" +
            "Would you like to download and install the update now?"));

        StringItem btnDownload = new StringItem(null, "Download Update (v" + newVer + ")", Item.BUTTON);
        btnDownload.setLayout(Item.LAYOUT_EXPAND | Item.LAYOUT_NEWLINE_AFTER);
        btnDownload.setDefaultCommand(cmdItemAction);
        btnDownload.setItemCommandListener(new ItemCommandListener() {
            public void commandAction(Command c, Item item) {
                try {
                    String base = serverUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    String target = (downloadUrl != null && downloadUrl.length() > 0) ? downloadUrl : base + "/download-app?platform=j2me";
                    platformRequest(target);
                } catch (Throwable ex) {
                    showAlert("Download Error", "Could not launch update: " + ex.getMessage(), AlertType.ERROR, uf);
                }
            }
        });
        uf.append(btnDownload);

        final Command cmdUpdateNow = new Command("Update", Command.SCREEN, 1);
        final Command cmdDismiss = new Command("Later", Command.BACK, 2);
        uf.addCommand(cmdUpdateNow);
        uf.addCommand(cmdDismiss);
        uf.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c == cmdUpdateNow) {
                    try {
                        String base = serverUrl.trim();
                        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                        String target = (downloadUrl != null && downloadUrl.length() > 0) ? downloadUrl : base + "/download-app?platform=j2me";
                        platformRequest(target);
                    } catch (Throwable ex) {
                        showAlert("Download Error", "Could not launch update: " + ex.getMessage(), AlertType.ERROR, uf);
                    }
                } else if (c == cmdDismiss) {
                    display.setCurrent(mainForm);
                }
            }
        });
        display.setCurrent(uf);
    }

    private static int[] parseVersionParts(String v) {
        int[] parts = new int[]{0, 0, 0};
        if (v == null) return parts;
        int pIdx = 0;
        int start = 0;
        int len = v.length();
        for (int i = 0; i <= len && pIdx < 3; i++) {
            if (i == len || v.charAt(i) == '.') {
                if (i > start) {
                    try {
                        parts[pIdx++] = Integer.parseInt(v.substring(start, i).trim());
                    } catch (Exception ex) {
                        if (pIdx > 0) parts[pIdx - 1] = 0;
                    }
                }
                start = i + 1;
            }
        }
        return parts;
    }

    private static boolean isNewerVersion(String remote, String current) {
        int[] r = parseVersionParts(remote);
        int[] c = parseVersionParts(current);
        for (int i = 0; i < 3; i++) {
            if (r[i] > c[i]) return true;
            if (r[i] < c[i]) return false;
        }
        return false;
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
        int len = json.length();
        while ((idx = json.indexOf(key, idx)) != -1) {
            if (idx == 0 || json.charAt(idx - 1) == '{' || json.charAt(idx - 1) == ',' || json.charAt(idx - 1) == ' ' || json.charAt(idx - 1) == '\n' || json.charAt(idx - 1) == '\r' || json.charAt(idx - 1) == '\t') {
                int colon = json.indexOf(':', idx + key.length());
                if (colon != -1) {
                    int start = colon + 1;
                    while (start < len && (json.charAt(start) == ' ' || json.charAt(start) == '\t' || json.charAt(start) == '\r' || json.charAt(start) == '\n')) {
                        start++;
                    }
                    if (start < len) {
                        if (json.charAt(start) == '\"') {
                            start++;
                            int end = start;
                            while (end < len) {
                                char c = json.charAt(end);
                                if (c == '\"') {
                                    int slashes = 0;
                                    int k = end - 1;
                                    while (k >= start && json.charAt(k) == '\\') {
                                        slashes++;
                                        k--;
                                    }
                                    if (slashes % 2 == 0) {
                                        break;
                                    }
                                }
                                end++;
                            }
                            if (end <= len) {
                                String unescaped = unescapeJson(json.substring(start, end));
                                if ("null".equalsIgnoreCase(unescaped)) {
                                    return null;
                                }
                                return unescaped;
                            }
                        } else {
                            int end = start;
                            while (end < len && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']' && json.charAt(end) != '\r' && json.charAt(end) != '\n') {
                                end++;
                            }
                            String val = json.substring(start, end).trim();
                            if ("null".equalsIgnoreCase(val)) {
                                return null;
                            }
                            return val;
                        }
                    }
                }
            }
            idx += key.length();
        }
        return null;
    }

    private static String unescapeJson(String s) {
        if (s == null || s.indexOf('\\') == -1) return s;
        StringBuffer sb = new StringBuffer();
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = s.charAt(i + 1);
                if (next == '\"') { sb.append('\"'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else if (next == '/') { sb.append('/'); i++; }
                else if (next == 'n') { sb.append('\n'); i++; }
                else if (next == 'r') { sb.append('\r'); i++; }
                else if (next == 't') { sb.append('\t'); i++; }
                else if (next == 'u' && i + 5 < len) {
                    try {
                        String hex = s.substring(i + 2, i + 6);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 5;
                    } catch (Exception ex) {
                        sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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

    private void showAlert(final String title, final String msg, final AlertType type, final Displayable next) {
        display.callSerially(new Runnable() {
            public void run() {
                try {
                    Alert alert = new Alert(title != null ? title : "Notice", msg != null ? msg : "", null, type);
                    alert.setTimeout(Alert.FOREVER);
                    display.setCurrent(alert, next != null ? next : mainForm);
                } catch (Throwable t) {
                    if (next != null) {
                        try { display.setCurrent(next); } catch (Throwable ignored) {}
                    }
                }
            }
        });
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
