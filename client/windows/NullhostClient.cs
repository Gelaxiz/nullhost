using System;
using System.IO;
using System.Net;
using System.Text;
using System.Drawing;
using System.Threading;
using System.Diagnostics;
using System.ComponentModel;
using System.Windows.Forms;
using Microsoft.Win32;
using System.Text.RegularExpressions;
using System.Security.Cryptography;
using System.Web.Script.Serialization;
using System.Collections.Generic;
using System.Reflection;

[assembly: AssemblyVersion("1.1.3.0")]
[assembly: AssemblyFileVersion("1.1.3.0")]

namespace NullhostClient
{
    public class ClientUpdate
    {
        public Version Version;
        public string DownloadUrl;
        public string Sha256;
    }

    public class UploadOptions
    {
        public string FilePath;
        public string[] FilePaths;
        public string CollectionId = "";
        public bool IsRawLink;
        public bool IsDownloadLink;
        public string CustomId = "";
        public string CustomFilename = "";
        public string Password = "";
        public int BurnLimit = 0;
        public bool IsPublic = false;
        public string UrlFormat = "both";
    }

    static class Program
    {
        public static string ServerUrl = "https://nullhost.gelaxiz.xyz";
        public static string AccountId = "";
        public static int DefaultExpiry = 604800;
        public static bool DarkMode = false;
        
        public static bool MenuNormal = true;
        public static bool MenuRaw = true;
        public static bool MenuDownload = true;
        public static bool MenuCustom = true;
        public static bool MenuGrouped = false;
        public static bool TosAccepted = false;
        public static readonly Version ClientVersion = new Version(1, 1, 3);

        public static CookieContainer Cookies = new CookieContainer();

        public static HttpWebRequest CreateRequest(string url, int timeoutMs)
        {
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(url);
            // Windows proxy auto-discovery can pause for tens of seconds on home
            // networks. Nullhost is always contacted directly over HTTPS.
            request.Proxy = null;
            request.Timeout = timeoutMs;
            request.ReadWriteTimeout = timeoutMs;
            request.UserAgent = "Nullhost-Windows/" + ClientVersion;
            return request;
        }
        
        [STAThread]
        static void Main(string[] args)
        {
            try {
                ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072; // TLS 1.2 on older .NET 4 profiles
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                
                LoadSettings();

                // Older builds registered "%*" as the Explorer placeholder. Some
                // Windows versions pass that text literally, which used to fall
                // through and open Settings once for every selected file.
                if (Array.IndexOf(args, "%*") >= 0) {
                    RegisterContextMenuCore();
                    return;
                }
                
                if (!TosAccepted)
                {
                    using (TosForm tf = new TosForm()) {
                        if (tf.ShowDialog() == DialogResult.OK) {
                            TosAccepted = true;
                            AccountId = tf.AccountId;
                            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(@"Software\Nullhost")) {
                                key.SetValue("TosAccepted", 1);
                                byte[] encrypted = ProtectedData.Protect(Encoding.UTF8.GetBytes(AccountId), null, DataProtectionScope.CurrentUser);
                                key.SetValue("AccountIdProtected", Convert.ToBase64String(encrypted));
                                try { key.DeleteValue("AccountId", false); } catch { }
                            }
                        } else {
                            return;
                        }
                    }
                }
                
                bool rawInvocation = Array.IndexOf(args, "-raw") >= 0;
                bool downloadInvocation = Array.IndexOf(args, "-download") >= 0;
                List<string> selectedFiles = CollectExplorerFiles(args, rawInvocation, downloadInvocation);
                if (selectedFiles == null) return; // This Explorer helper joined the leading upload process.
                if (selectedFiles.Count > 0)
                {
                    // Update check when the app is used (e.g. file upload)
                    try {
                        ClientUpdate update = CheckForUpdate(2500);
                        if (update != null) {
                            DialogResult dr = MessageBox.Show(
                                "A newer version of Nullhost (" + update.Version + ") is available.\n\nWould you like to download and install the update now?",
                                "Nullhost Update",
                                MessageBoxButtons.YesNo,
                                MessageBoxIcon.Information);
                            if (dr == DialogResult.Yes) {
                                DownloadAndInstallUpdate(update, args);
                                return;
                            }
                        }
                    } catch { }

                    UploadOptions opt = new UploadOptions() { FilePath = selectedFiles[0], FilePaths = selectedFiles.ToArray() };
                    opt.IsRawLink = rawInvocation;
                    opt.IsDownloadLink = downloadInvocation;
                    
                    if (Array.IndexOf(args, "-custom") >= 0 && selectedFiles.Count == 1) {
                        using (AdvancedUploadForm f = new AdvancedUploadForm(opt)) {
                            if (f.ShowDialog() != DialogResult.OK) return;
                        }
                    }
                    
                    Application.Run(new UploadContext(opt));
                }
                else if (Array.IndexOf(args, "-upload") >= 0)
                {
                    // A malformed Explorer invocation must never open Settings.
                    // Refresh the verb silently; the next click will carry %1.
                    RegisterContextMenuCore();
                    return;
                }
                else
                {
                    Application.Run(new SettingsForm());
                }
            } catch (Exception ex) {
                MessageBox.Show("Fatal Error: " + ex.Message);
            }
        }

        private static List<string> CollectExplorerFiles(string[] args, bool rawInvocation, bool downloadInvocation)
        {
            List<string> directFiles = new List<string>();
            foreach (string arg in args) if (File.Exists(arg)) directFiles.Add(Path.GetFullPath(arg));
            if (directFiles.Count != 1 || Array.IndexOf(args, "-upload") < 0) return directFiles;

            string queuePath = Path.Combine(Path.GetTempPath(), "nullhost-upload-" + Environment.UserName + ".queue");
            bool isLeader;
            using (Mutex batchMutex = new Mutex(true, @"Local\NullhostUploadBatch", out isLeader)) {
                if (!isLeader) batchMutex.WaitOne();
                try {
                    string linkMode = downloadInvocation ? "download" : (rawInvocation ? "raw" : "link");
                    string record = linkMode + "\t" + Convert.ToBase64String(Encoding.UTF8.GetBytes(directFiles[0])) + Environment.NewLine;
                    File.AppendAllText(queuePath, record, Encoding.UTF8);
                } finally {
                    batchMutex.ReleaseMutex();
                }

                if (!isLeader) return null;
                Thread.Sleep(700); // Give Explorer's remaining per-file launches time to join this batch.
                batchMutex.WaitOne();
                try {
                    List<string> batch = new List<string>();
                    if (File.Exists(queuePath)) {
                        foreach (string line in File.ReadAllLines(queuePath, Encoding.UTF8)) {
                            string[] fields = line.Split(new char[] { '\t' }, 2);
                            string expectedMode = downloadInvocation ? "download" : (rawInvocation ? "raw" : "link");
                            if (fields.Length != 2 || fields[0] != expectedMode) continue;
                            try {
                                string path = Encoding.UTF8.GetString(Convert.FromBase64String(fields[1]));
                                if (File.Exists(path) && !batch.Contains(path)) batch.Add(path);
                            } catch { }
                        }
                        try { File.Delete(queuePath); } catch { }
                    }
                    return batch.Count > 0 ? batch : directFiles;
                } finally {
                    batchMutex.ReleaseMutex();
                }
            }
        }
        
        public static void LoadSettings()
        {
            try {
                using (RegistryKey key = Registry.CurrentUser.CreateSubKey(@"Software\Nullhost")) {
                    ServerUrl = (string)key.GetValue("ServerUrl", "https://nullhost.gelaxiz.xyz");
                    string protectedAccount = (string)key.GetValue("AccountIdProtected", "");
                    if (!string.IsNullOrEmpty(protectedAccount)) {
                        byte[] encrypted = Convert.FromBase64String(protectedAccount);
                        AccountId = Encoding.UTF8.GetString(ProtectedData.Unprotect(encrypted, null, DataProtectionScope.CurrentUser));
                    } else {
                        AccountId = (string)key.GetValue("AccountId", "");
                    }
                    DefaultExpiry = (int)key.GetValue("DefaultExpiry", 604800);
                    DarkMode = (int)key.GetValue("DarkMode", 0) == 1;
                    
                    MenuNormal = (int)key.GetValue("MenuNormal", 1) == 1;
                    MenuRaw = (int)key.GetValue("MenuRaw", 1) == 1;
                    MenuDownload = (int)key.GetValue("MenuDownload", 1) == 1;
                    MenuCustom = (int)key.GetValue("MenuCustom", 1) == 1;
                    MenuGrouped = (int)key.GetValue("MenuGrouped", 0) == 1;
                    TosAccepted = (int)key.GetValue("TosAccepted", 0) == 1;
                }
            } catch { }
        }
        
        public static void SaveSettings(string url, string acc, int expiry, bool dark)
        {
            try {
                using (RegistryKey key = Registry.CurrentUser.CreateSubKey(@"Software\Nullhost")) {
                    key.SetValue("ServerUrl", url);
                    byte[] encrypted = ProtectedData.Protect(Encoding.UTF8.GetBytes(acc), null, DataProtectionScope.CurrentUser);
                    key.SetValue("AccountIdProtected", Convert.ToBase64String(encrypted));
                    try { key.DeleteValue("AccountId", false); } catch { }
                    key.SetValue("DefaultExpiry", expiry);
                    key.SetValue("DarkMode", dark ? 1 : 0);
                    
                    key.SetValue("MenuNormal", MenuNormal ? 1 : 0);
                    key.SetValue("MenuRaw", MenuRaw ? 1 : 0);
                    key.SetValue("MenuDownload", MenuDownload ? 1 : 0);
                    key.SetValue("MenuCustom", MenuCustom ? 1 : 0);
                    key.SetValue("MenuGrouped", MenuGrouped ? 1 : 0);
                }
                ServerUrl = url;
                AccountId = acc;
                DefaultExpiry = expiry;
                DarkMode = dark;
            } catch { }
        }
        
        public static void RemoveContextMenuCore()
        {
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\nullhost", false); } catch {}
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\nullhost_normal", false); } catch {}
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\nullhost_raw", false); } catch {}
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\nullhost_download", false); } catch {}
            try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\nullhost_custom", false); } catch {}
            // Clean up old machine-wide ones if possible
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\nullhost", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\nullhost_normal", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\nullhost_raw", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\nullhost_download", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\nullhost_custom", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\Nullhost", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\Nullhost_Normal", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\Nullhost_Raw", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\Nullhost_Custom", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\Nullhost Upload", false); } catch {}
            try { Registry.ClassesRoot.DeleteSubKeyTree(@"*\shell\Nullhost Upload Raw", false); } catch {}
        }

        public static void RemoveContextMenu()
        {
            try {
                RemoveContextMenuCore();
                MessageBox.Show("Context Menu Removed!");
            } catch (Exception ex) {
                MessageBox.Show("Error: " + ex.Message);
            }
        }

        public static void RegisterContextMenuCore()
        {
            RemoveContextMenuCore();
            if (!MenuNormal && !MenuRaw && !MenuDownload && !MenuCustom) return;

            if (MenuGrouped) {
                using (RegistryKey root = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\nullhost")) {
                    root.SetValue("MUIVerb", "Upload to nullhost");
                    root.SetValue("Icon", Application.ExecutablePath + ",0");
                    root.SetValue("SubCommands", "");
                    root.SetValue("MultiSelectModel", "Player");
                    
                    using (RegistryKey shell = root.CreateSubKey("shell")) {
                        if (MenuNormal) {
                            using (RegistryKey k = shell.CreateSubKey("cmd1")) {
                                k.SetValue("MUIVerb", "link");
                                k.SetValue("Icon", Application.ExecutablePath + ",0");
                                k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload \"%1\"");
                            }
                        }
                        if (MenuRaw) {
                            using (RegistryKey k = shell.CreateSubKey("cmd2")) {
                                k.SetValue("MUIVerb", "raw");
                                k.SetValue("Icon", Application.ExecutablePath + ",0");
                                k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload -raw \"%1\"");
                            }
                        }
                        if (MenuDownload) {
                            using (RegistryKey k = shell.CreateSubKey("cmd3")) {
                                k.SetValue("MUIVerb", "quick download link");
                                k.SetValue("Icon", Application.ExecutablePath + ",0");
                                k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload -download \"%1\"");
                            }
                        }
                        if (MenuCustom) {
                            using (RegistryKey k = shell.CreateSubKey("cmd4")) {
                                k.SetValue("MUIVerb", "custom");
                                k.SetValue("Icon", Application.ExecutablePath + ",0");
                                k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload -custom \"%1\"");
                            }
                        }
                    }
                }
            } else {
                if (MenuNormal) {
                    using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\nullhost_normal")) {
                        k.SetValue("MUIVerb", "Upload to nullhost (link)");
                        k.SetValue("Icon", Application.ExecutablePath + ",0");
                        k.SetValue("MultiSelectModel", "Player");
                        k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload \"%1\"");
                    }
                }
                if (MenuRaw) {
                    using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\nullhost_raw")) {
                        k.SetValue("MUIVerb", "Upload to nullhost (raw)");
                        k.SetValue("Icon", Application.ExecutablePath + ",0");
                        k.SetValue("MultiSelectModel", "Player");
                        k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload -raw \"%1\"");
                    }
                }
                if (MenuDownload) {
                    using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\nullhost_download")) {
                        k.SetValue("MUIVerb", "Upload to nullhost (quick download link)");
                        k.SetValue("Icon", Application.ExecutablePath + ",0");
                        k.SetValue("MultiSelectModel", "Player");
                        k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload -download \"%1\"");
                    }
                }
                if (MenuCustom) {
                    using (RegistryKey k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\nullhost_custom")) {
                        k.SetValue("MUIVerb", "Upload to nullhost (custom)");
                        k.SetValue("Icon", Application.ExecutablePath + ",0");
                        k.SetValue("MultiSelectModel", "Player");
                        k.CreateSubKey("command").SetValue("", "\"" + Application.ExecutablePath + "\" -upload -custom \"%1\"");
                    }
                }
            }
        }

        public static void RegisterContextMenu()
        {
            try {
                RegisterContextMenuCore();
                MessageBox.Show("Context Menu Updated!");
            } catch (Exception ex) {
                MessageBox.Show("Error: " + ex.Message);
            }
        }
        
        public static void Login()
        {
            if (string.IsNullOrEmpty(AccountId)) return;
            try {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create(ServerUrl + "/a");
                req.Method = "POST";
                req.ContentType = "application/x-www-form-urlencoded";
                req.CookieContainer = Cookies;
                req.Headers["X-Nullhost-Account"] = AccountId;
                byte[] data = Encoding.UTF8.GetBytes("action=login&account_id=" + Uri.EscapeDataString(AccountId));
                req.ContentLength = data.Length;
                using (Stream s = req.GetRequestStream()) s.Write(data, 0, data.Length);
                using (WebResponse resp = req.GetResponse()) { }
            } catch { }
        }
        
        public static void SetClipboardText(string text)
        {
            Thread t = new Thread(new ThreadStart(delegate {
                try { Clipboard.SetText(text); } catch { }
            }));
            t.SetApartmentState(ApartmentState.STA);
            t.Start();
            t.Join();
        }

        public static ClientUpdate CheckForUpdate(int timeoutMs = 3000)
        {
            try {
                HttpWebRequest request = CreateRequest(ServerUrl.TrimEnd(new char[] { '/' }) + "/api/client/update", timeoutMs);
                request.Method = "GET";
                request.Headers[HttpRequestHeader.CacheControl] = "no-cache";
                using (WebResponse response = request.GetResponse())
                using (StreamReader reader = new StreamReader(response.GetResponseStream())) {
                    string json = reader.ReadToEnd();
                    Dictionary<string, object> manifest = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(json);
                    if (manifest == null) return null;
                    Version version;
                    string versionText = manifest.ContainsKey("version") ? Convert.ToString(manifest["version"]) : "";
                    string downloadUrl = manifest.ContainsKey("download_url") ? Convert.ToString(manifest["download_url"]) : "";
                    string sha256 = manifest.ContainsKey("sha256") ? Convert.ToString(manifest["sha256"]) : "";
                    Uri serverUri, downloadUri;
                    if (!Version.TryParse(versionText, out version) || version <= ClientVersion) return null;
                    if (!Uri.TryCreate(ServerUrl, UriKind.Absolute, out serverUri) ||
                        !Uri.TryCreate(downloadUrl, UriKind.Absolute, out downloadUri) ||
                        (downloadUri.Scheme != Uri.UriSchemeHttps && !serverUri.IsLoopback) ||
                        !string.Equals(serverUri.Host, downloadUri.Host, StringComparison.OrdinalIgnoreCase) ||
                        !Regex.IsMatch(sha256 ?? "", "^[0-9a-fA-F]{64}$"))
                        return null;
                    return new ClientUpdate { Version = version, DownloadUrl = downloadUrl, Sha256 = sha256.ToLowerInvariant() };
                }
            } catch {
                return null;
            }
        }

        public static void DownloadAndInstallUpdate(ClientUpdate update, string[] relaunchArgs = null)
        {
            string currentExe = Application.ExecutablePath;
            string downloadPath = Path.Combine(Path.GetTempPath(), "Nullhost-" + update.Version + ".exe.download");
            HttpWebRequest request = CreateRequest(update.DownloadUrl, 120000);
            request.Method = "GET";
            using (WebResponse response = request.GetResponse())
            using (Stream input = response.GetResponseStream())
            using (FileStream output = new FileStream(downloadPath, FileMode.Create, FileAccess.Write, FileShare.None))
                input.CopyTo(output);
            string actual;
            using (FileStream stream = File.OpenRead(downloadPath))
            using (SHA256 hash = SHA256.Create())
                actual = BitConverter.ToString(hash.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
            if (!string.Equals(actual, update.Sha256, StringComparison.OrdinalIgnoreCase)) {
                try { File.Delete(downloadPath); } catch { }
                throw new InvalidDataException("The downloaded update failed verification.");
            }

            string scriptPath = Path.Combine(Path.GetTempPath(), "nullhost-update-" + Guid.NewGuid().ToString("N") + ".cmd");
            string launchArgsStr = "";
            if (relaunchArgs != null && relaunchArgs.Length > 0) {
                List<string> escaped = new List<string>();
                foreach (string a in relaunchArgs) escaped.Add("\"" + a.Replace("\"", "\\\"") + "\"");
                launchArgsStr = string.Join(" ", escaped.ToArray());
            } else {
                launchArgsStr = "-updated";
            }
            string script = "@echo off\r\nsetlocal\r\n" +
                "for /l %%i in (1,1,30) do (\r\n" +
                "  move /y \"" + downloadPath + "\" \"" + currentExe + "\" >nul 2>&1 && goto launch\r\n" +
                "  ping 127.0.0.1 -n 2 >nul\r\n" +
                ")\r\nexit /b 1\r\n:launch\r\n" +
                "start \"\" \"" + currentExe + "\" " + launchArgsStr + "\r\n" +
                "del \"%~f0\"\r\n";
            File.WriteAllText(scriptPath, script, Encoding.ASCII);
            Process.Start(new ProcessStartInfo(scriptPath) { UseShellExecute = true, WindowStyle = ProcessWindowStyle.Hidden });
            Application.Exit();
            Environment.Exit(0);
        }

        public static void DeleteUploadedItems(string[] itemIds, string collectionId)
        {
            if (itemIds == null || itemIds.Length == 0) throw new InvalidOperationException("No uploaded files were available to delete.");
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(ServerUrl + "/api/items/delete-uploaded");
            request.Method = "POST";
            request.ContentType = "application/json";
            request.CookieContainer = Cookies;
            request.Headers["X-Nullhost-Account"] = AccountId;
            Dictionary<string, object> payload = new Dictionary<string, object>();
            payload["item_ids"] = itemIds;
            payload["collection_id"] = collectionId ?? "";
            payload["confirm_delete"] = true;
            byte[] body = Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(payload));
            request.ContentLength = body.Length;
            using (Stream stream = request.GetRequestStream()) stream.Write(body, 0, body.Length);
            using (WebResponse response = request.GetResponse()) { }
        }

        public static void ApplyTheme(Form f)
        {
            if (DarkMode) {
                f.BackColor = Color.FromArgb(30, 30, 30);
                f.ForeColor = Color.FromArgb(220, 220, 220);
                foreach (Control c in f.Controls) {
                    if (c is Button || c is TextBox || c is ComboBox || c is CheckBox || c is NumericUpDown || c is GroupBox) {
                        c.BackColor = Color.FromArgb(50, 50, 50);
                        c.ForeColor = Color.White;
                        if (c is GroupBox gb) {
                            foreach (Control sub in gb.Controls) {
                                sub.BackColor = Color.FromArgb(50, 50, 50);
                                sub.ForeColor = Color.White;
                            }
                        }
                    }
                    if (c is Label) c.ForeColor = Color.White;
                }
            } else {
                f.BackColor = SystemColors.Control;
                f.ForeColor = SystemColors.ControlText;
                foreach (Control c in f.Controls) {
                    if (c is Label) c.ForeColor = SystemColors.ControlText;
                }
            }
        }
    }

    public class TosForm : Form
    {
        public string AccountId = "";

        public TosForm()
        {
            this.Text = "nullhost - welcome";
            this.Size = new Size(400, 320);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.StartPosition = FormStartPosition.CenterScreen;
            this.MaximizeBox = false;
            
            Label lblTos = new Label() { Text = "by continuing, you agree to our terms of service:\n- you own what you upload.\n- we are a dumb pipe and do not monitor uploads.\n- no guarantees are provided for uptime or data safety.\n- we are not liable for any damages.", Location = new Point(20, 20), Size = new Size(340, 100) };
            
            Label lblAcc = new Label() { Text = "account id (required, 16 or 24 digits):", Location = new Point(20, 130), Size = new Size(340, 20) };
            TextBox txtAcc = new TextBox() { Location = new Point(20, 150), Size = new Size(340, 20) };
            
            Button btnAccept = new Button() { Text = "accept terms & continue", Location = new Point(20, 200), Size = new Size(160, 30) };
            Button btnDecline = new Button() { Text = "decline & exit", Location = new Point(200, 200), Size = new Size(160, 30) };
            
            btnAccept.Click += (s, e) => {
                if (!Regex.IsMatch(txtAcc.Text.Trim(), "^(?:[0-9]{16}|[0-9]{24})$")) {
                    MessageBox.Show("A valid 16 or 24 digit account ID is required.");
                    return;
                }
                AccountId = txtAcc.Text.Trim();
                this.DialogResult = DialogResult.OK;
                this.Close();
            };
            
            btnDecline.Click += (s, e) => {
                this.DialogResult = DialogResult.Cancel;
                this.Close();
            };
            
            this.Controls.Add(lblTos);
            this.Controls.Add(lblAcc);
            this.Controls.Add(txtAcc);
            this.Controls.Add(btnAccept);
            this.Controls.Add(btnDecline);
            
            Program.ApplyTheme(this);
        }
    }

    public class AdvancedUploadForm : Form
    {
        public UploadOptions Options;
        TextBox txtUrl, txtName, txtPwd;
        NumericUpDown numBurn;
        CheckBox chkPub;
        ComboBox cbFormat;

        public AdvancedUploadForm(UploadOptions opt)
        {
            Options = opt;
            this.Text = "nullhost - custom upload";
            this.Size = new Size(350, 320);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.StartPosition = FormStartPosition.CenterScreen;
            this.MaximizeBox = false;
            this.ShowInTaskbar = false;

            Label lblUrl = new Label() { Text = "Custom Link:", Location = new Point(10, 15), AutoSize = true };
            txtUrl = new TextBox() { Location = new Point(130, 12), Width = 190 };
            
            Label lblName = new Label() { Text = "Custom Filename:", Location = new Point(10, 45), AutoSize = true };
            txtName = new TextBox() { Location = new Point(130, 42), Width = 190 };
            
            Label lblPwd = new Label() { Text = "Password:", Location = new Point(10, 75), AutoSize = true };
            txtPwd = new TextBox() { Location = new Point(130, 72), Width = 190, UseSystemPasswordChar = true };

            Label lblBurn = new Label() { Text = "Burn Limit:", Location = new Point(10, 105), AutoSize = true };
            numBurn = new NumericUpDown() { Location = new Point(130, 102), Width = 190, Maximum = 99999, Minimum = 0 };

            Label lblFmt = new Label() { Text = "URL Format:", Location = new Point(10, 135), AutoSize = true };
            cbFormat = new ComboBox() { Location = new Point(130, 132), Width = 190, DropDownStyle = ComboBoxStyle.DropDownList };
            cbFormat.Items.AddRange(new object[] { "Both", "Letters", "Numbers" });
            cbFormat.SelectedIndex = 0;

            chkPub = new CheckBox() { Text = "Publicly Visible", Location = new Point(130, 165), Width = 190 };
            
            Button btnUpload = new Button() { Text = "Upload", Location = new Point(10, 200), Width = 150, Height = 40 };
            btnUpload.Click += new EventHandler(BtnUpload_Click);

            Button btnCancel = new Button() { Text = "Cancel", Location = new Point(170, 200), Width = 150, Height = 40 };
            btnCancel.Click += new EventHandler((s,e) => { this.DialogResult = DialogResult.Cancel; this.Close(); });

            this.Controls.AddRange(new Control[] { lblUrl, txtUrl, lblName, txtName, lblPwd, txtPwd, lblBurn, numBurn, lblFmt, cbFormat, chkPub, btnUpload, btnCancel });
            Program.ApplyTheme(this);
        }

        private void BtnUpload_Click(object sender, EventArgs e)
        {
            Options.CustomId = txtUrl.Text.Trim();
            Options.CustomFilename = txtName.Text.Trim();
            Options.Password = txtPwd.Text;
            Options.BurnLimit = (int)numBurn.Value;
            Options.IsPublic = chkPub.Checked;
            Options.UrlFormat = cbFormat.SelectedItem.ToString().ToLower();
            
            this.DialogResult = DialogResult.OK;
            this.Close();
        }
    }
    
    public class SettingsForm : Form
    {
        TextBox txtUrl, txtAcc;
        ComboBox cbExpiry;
        NumericUpDown numCustomExpiry;
        CheckBox chkDark;
        CheckBox chkMenuNormal, chkMenuRaw, chkMenuDownload, chkMenuCustom, chkMenuGrouped;
        Button btnUpdate;
        
        public SettingsForm()
        {
            this.Text = "nullhost settings";
            this.Size = new Size(350, 465);
            this.FormBorderStyle = FormBorderStyle.FixedDialog;
            this.StartPosition = FormStartPosition.CenterScreen;
            this.MaximizeBox = false;
            this.ShowInTaskbar = false;
            
            Label lblUrl = new Label() { Text = "Server URL:", Location = new Point(10, 15), AutoSize = true };
            txtUrl = new TextBox() { Location = new Point(120, 12), Width = 200, Text = Program.ServerUrl };
            
            Label lblAcc = new Label() { Text = "Account ID:", Location = new Point(10, 45), AutoSize = true };
            txtAcc = new TextBox() { Location = new Point(120, 42), Width = 200, Text = Program.AccountId };
            
            Label lblExp = new Label() { Text = "Default Expiry:", Location = new Point(10, 75), AutoSize = true };
            cbExpiry = new ComboBox() { Location = new Point(120, 72), Width = 200, DropDownStyle = ComboBoxStyle.DropDownList };
            cbExpiry.Items.AddRange(new object[] { "1 Hour", "1 Day", "1 Week", "1 Month", "6 Months", "1 Year", "5 Years", "Custom" });
            
            numCustomExpiry = new NumericUpDown() { Location = new Point(120, 102), Width = 200, Minimum = 1, Maximum = 1825, Visible = false };
            Label lblCustomExp = new Label() { Text = "Custom (Days):", Location = new Point(10, 105), AutoSize = true, Visible = false };

            cbExpiry.SelectedIndexChanged += new EventHandler((s, e) => {
                bool isCustom = cbExpiry.SelectedItem.ToString() == "Custom";
                numCustomExpiry.Visible = isCustom;
                lblCustomExp.Visible = isCustom;
            });
            
            switch (Program.DefaultExpiry) {
                case 3600: cbExpiry.SelectedIndex = 0; break;
                case 86400: cbExpiry.SelectedIndex = 1; break;
                case 604800: cbExpiry.SelectedIndex = 2; break;
                case 2592000: cbExpiry.SelectedIndex = 3; break;
                case 15552000: cbExpiry.SelectedIndex = 4; break;
                case 31536000: cbExpiry.SelectedIndex = 5; break;
                case 157680000: cbExpiry.SelectedIndex = 6; break;
                default: 
                    cbExpiry.SelectedIndex = 7; 
                    numCustomExpiry.Value = Program.DefaultExpiry / 86400 > 0 ? Program.DefaultExpiry / 86400 : 1;
                    break;
            }
            
            chkDark = new CheckBox() { Text = "Dark Mode", Location = new Point(120, 130), Width = 200, Checked = Program.DarkMode };
            
            GroupBox gbMenu = new GroupBox() { Text = "Context Menu Settings", Location = new Point(10, 160), Size = new Size(310, 125) };
            chkMenuNormal = new CheckBox() { Text = "Normal Link", Location = new Point(10, 20), Width = 100, Checked = Program.MenuNormal };
            chkMenuRaw = new CheckBox() { Text = "Raw Link", Location = new Point(10, 45), Width = 100, Checked = Program.MenuRaw };
            chkMenuDownload = new CheckBox() { Text = "Download Link", Location = new Point(10, 70), Width = 110, Checked = Program.MenuDownload };
            chkMenuCustom = new CheckBox() { Text = "Custom", Location = new Point(10, 95), Width = 100, Checked = Program.MenuCustom };
            chkMenuGrouped = new CheckBox() { Text = "Group into folder (Win7+)", Location = new Point(120, 20), Width = 180, Checked = Program.MenuGrouped };
            gbMenu.Controls.AddRange(new Control[] { chkMenuNormal, chkMenuRaw, chkMenuDownload, chkMenuCustom, chkMenuGrouped });
            
            Button btnReg = new Button() { Text = "Update Menu", Location = new Point(10, 295), Width = 150 };
            btnReg.Click += new EventHandler((s,e) => { 
                Program.MenuNormal = chkMenuNormal.Checked;
                Program.MenuRaw = chkMenuRaw.Checked;
                Program.MenuDownload = chkMenuDownload.Checked;
                Program.MenuCustom = chkMenuCustom.Checked;
                Program.MenuGrouped = chkMenuGrouped.Checked;
                Program.RegisterContextMenu(); 
            });

            Button btnUnreg = new Button() { Text = "Remove Menu", Location = new Point(170, 295), Width = 150 };
            btnUnreg.Click += new EventHandler((s,e) => { Program.RemoveContextMenu(); });
            
            btnUpdate = new Button() { Text = "Check for Updates", Location = new Point(10, 335), Width = 310, Height = 30 };
            btnUpdate.Click += new EventHandler((s,e) => BeginUpdateCheck(true));

            Button btnSave = new Button() { Text = "Save Settings", Location = new Point(10, 375), Width = 310, Height = 30 };
            btnSave.Click += new EventHandler(BtnSave_Click);
            
            this.Controls.AddRange(new Control[] { lblUrl, txtUrl, lblAcc, txtAcc, lblExp, cbExpiry, numCustomExpiry, lblCustomExp, chkDark, gbMenu, btnReg, btnUnreg, btnUpdate, btnSave });
            
            Program.ApplyTheme(this);
            this.Shown += new EventHandler((s,e) => BeginUpdateCheck(false));
        }

        private void BeginUpdateCheck(bool userRequested)
        {
            btnUpdate.Enabled = false;
            btnUpdate.Text = "Checking...";
            ThreadPool.QueueUserWorkItem(delegate {
                ClientUpdate update = null;
                Exception failure = null;
                try { update = Program.CheckForUpdate(); } catch (Exception ex) { failure = ex; }
                if (IsDisposed) return;
                BeginInvoke(new MethodInvoker(delegate {
                    btnUpdate.Enabled = true;
                    btnUpdate.Text = "Check for Updates";
                    if (failure != null) {
                        if (userRequested) MessageBox.Show(this, "Could not check for updates: " + failure.Message, "nullhost update", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        return;
                    }
                    if (update == null) {
                        if (userRequested) MessageBox.Show(this, "Nullhost is up to date.", "nullhost update", MessageBoxButtons.OK, MessageBoxIcon.Information);
                        return;
                    }
                    if (MessageBox.Show(this, "A newer version of Nullhost (" + update.Version + ") is available.\n\nWould you like to download and install the update now?", "Nullhost Update", MessageBoxButtons.YesNo, MessageBoxIcon.Information) != DialogResult.Yes) return;
                    try {
                        btnUpdate.Enabled = false;
                        btnUpdate.Text = "Updating...";
                        Program.DownloadAndInstallUpdate(update);
                    }
                    catch (Exception ex) {
                        btnUpdate.Enabled = true;
                        btnUpdate.Text = "Check for Updates";
                        MessageBox.Show(this, "Update failed: " + ex.Message, "Nullhost Update", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    }
                }));
            });
        }
        
        private void BtnSave_Click(object sender, EventArgs e)
        {
            Uri serverUri;
            string serverValue = txtUrl.Text.Trim().TrimEnd(new char[] { '/' });
            if (!Uri.TryCreate(serverValue, UriKind.Absolute, out serverUri) ||
                (serverUri.Scheme != Uri.UriSchemeHttps && !serverUri.IsLoopback)) {
                MessageBox.Show("Server URL must use HTTPS (localhost is allowed for testing).");
                return;
            }
            string accountValue = txtAcc.Text.Trim();
            if (!Regex.IsMatch(accountValue, "^(?:[0-9]{16}|[0-9]{24})$")) {
                MessageBox.Show("Account ID must contain 16 or 24 digits.");
                return;
            }
            int exp = 604800;
            switch(cbExpiry.SelectedIndex) {
                case 0: exp = 3600; break;
                case 1: exp = 86400; break;
                case 2: exp = 604800; break;
                case 3: exp = 2592000; break;
                case 4: exp = 15552000; break;
                case 5: exp = 31536000; break;
                case 6: exp = 157680000; break;
                case 7: exp = (int)(numCustomExpiry.Value * 86400); break;
            }
            Program.MenuNormal = chkMenuNormal.Checked;
            Program.MenuRaw = chkMenuRaw.Checked;
            Program.MenuDownload = chkMenuDownload.Checked;
            Program.MenuCustom = chkMenuCustom.Checked;
            Program.MenuGrouped = chkMenuGrouped.Checked;

            Program.SaveSettings(serverValue, accountValue, exp, chkDark.Checked);
            MessageBox.Show("Settings saved.");
            this.Close();
        }
    }

    public class NotificationForm : Form
    {
        private Label lblStatus;
        private ProgressBar pb;
        private System.Windows.Forms.Timer closeTimer;

        protected override bool ShowWithoutActivation
        {
            get { return true; }
        }

        public NotificationForm(int mode, string message = "", string openUrl = "", string[] uploadedItemIds = null)
        {
            this.Text = "nullhost";
            this.FormBorderStyle = FormBorderStyle.None;
            this.StartPosition = FormStartPosition.Manual;
            this.TopMost = true;
            this.ShowInTaskbar = false;

            Color textColor = Program.DarkMode ? Color.White : Color.Black;

            if (mode == 0) {
                this.Size = new Size(380, 68);
                lblStatus = new Label() { Text = "Uploading... 0%", Location = new Point(10, 10), Width = 360, Height = 18, AutoSize = false, ForeColor = textColor };
                pb = new ProgressBar() { Location = new Point(10, 32), Width = 360, Height = 16, Style = ProgressBarStyle.Continuous };
                this.Controls.AddRange(new Control[] { lblStatus, pb });
            } else {
                bool hasActions = !string.IsNullOrEmpty(openUrl);
                this.Size = new Size(hasActions ? 480 : 390, 48);
                lblStatus = new Label() { Text = mode == 1 ? "Upload Complete! Link Copied." : "Error: " + message, Location = new Point(10, 12), AutoSize = true, Font = new Font(this.Font, FontStyle.Bold), ForeColor = mode == 2 ? Color.Red : textColor };
                this.Controls.Add(lblStatus);
                if (!string.IsNullOrEmpty(openUrl)) {
                    Button openButton = new Button() { Text = "open link", Location = new Point(290, 9), Size = new Size(82, 30) };
                    openButton.Click += delegate { Process.Start(openUrl); this.Close(); };
                    this.Controls.Add(openButton);
                    Button deleteButton = new Button() { Text = "del upload", Location = new Point(378, 9), Size = new Size(92, 30) };
                    deleteButton.Click += delegate {
                        if (MessageBox.Show("Permanently delete the file you just uploaded?", "nullhost", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
                        try { Program.DeleteUploadedItems(uploadedItemIds, ""); this.Close(); }
                        catch (Exception ex) { MessageBox.Show("Could not delete upload: " + ex.Message); }
                    };
                    this.Controls.Add(deleteButton);
                }

                closeTimer = new System.Windows.Forms.Timer();
                closeTimer.Interval = 5000;
                closeTimer.Tick += new EventHandler((s, e) => { this.Close(); });
                closeTimer.Start();
            }

            Rectangle workingArea = Screen.PrimaryScreen.WorkingArea;
            this.Location = new Point(workingArea.Right - this.Width - 10, workingArea.Bottom - this.Height - 10);

            this.Paint += new PaintEventHandler((s, e) => {
                Color c = Program.DarkMode ? Color.Gray : Color.DarkGray;
                ControlPaint.DrawBorder(e.Graphics, this.ClientRectangle, c, ButtonBorderStyle.Solid);
            });
            
            Program.ApplyTheme(this);
        }

        public void UpdateProgress(int percent, string details = null)
        {
            if (this.InvokeRequired)
            {
                this.Invoke(new MethodInvoker(() => UpdateProgress(percent, details)));
                return;
            }
            if (pb != null) {
                pb.Value = Math.Max(0, Math.Min(100, percent));
                lblStatus.Text = string.IsNullOrEmpty(details) ? ("Uploading... " + percent + "%") : details;
            }
        }
    }

    public class CollectionNotificationForm : Form
    {
        private System.Windows.Forms.Timer closeTimer;

        public CollectionNotificationForm(string collectionId, string collectionUrl, string[] uploadedItemIds)
        {
            this.Text = "nullhost";
            this.FormBorderStyle = FormBorderStyle.None;
            this.StartPosition = FormStartPosition.Manual;
            this.TopMost = true;
            this.ShowInTaskbar = false;
            this.Size = new Size(458, 76);
            Label message = new Label() { Text = "Collection created. Info page copied.", Location = new Point(10, 8), AutoSize = true };
            Button nameButton = new Button() { Text = "name", Location = new Point(10, 36), Size = new Size(76, 28) };
            Button openButton = new Button() { Text = "open link", Location = new Point(294, 36), Size = new Size(72, 28) };
            Button deleteButton = new Button() { Text = "del files", Location = new Point(374, 36), Size = new Size(74, 28) };
            TextBox nameBox = new TextBox() { Location = new Point(10, 38), Width = 210, Visible = false, MaxLength = 80 };
            Button saveButton = new Button() { Text = "save", Location = new Point(226, 36), Width = 60, Height = 28, Visible = false };
            this.Controls.AddRange(new Control[] { message, nameButton, openButton, deleteButton, nameBox, saveButton });
            openButton.Click += delegate { Process.Start(collectionUrl); this.Close(); };
            deleteButton.Click += delegate {
                closeTimer.Stop();
                if (MessageBox.Show("Permanently delete every file from this upload and remove the collection?", "nullhost", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) { closeTimer.Start(); return; }
                try { Program.DeleteUploadedItems(uploadedItemIds, collectionId); this.Close(); }
                catch (Exception ex) { MessageBox.Show("Could not delete uploads: " + ex.Message); closeTimer.Start(); }
            };
            nameButton.Click += delegate {
                closeTimer.Stop();
                nameButton.Visible = false;
                nameBox.Visible = true;
                saveButton.Visible = true;
                nameBox.Focus();
            };
            saveButton.Click += delegate {
                string name = nameBox.Text.Trim();
                if (name.Length == 0) return;
                try {
                    HttpWebRequest request = (HttpWebRequest)WebRequest.Create(Program.ServerUrl + "/collections/" + Uri.EscapeDataString(collectionId) + "/name");
                    request.Method = "POST";
                    request.ContentType = "application/json";
                    request.CookieContainer = Program.Cookies;
                    request.Headers["X-Nullhost-Account"] = Program.AccountId;
                    string json = new JavaScriptSerializer().Serialize(new Dictionary<string, object>() { { "name", name } });
                    byte[] body = Encoding.UTF8.GetBytes(json);
                    request.ContentLength = body.Length;
                    using (Stream stream = request.GetRequestStream()) stream.Write(body, 0, body.Length);
                    using (WebResponse response = request.GetResponse()) { }
                    message.Text = "Collection renamed.";
                    nameBox.Visible = false;
                    saveButton.Visible = false;
                    nameButton.Visible = true;
                    closeTimer.Start();
                } catch (Exception ex) {
                    MessageBox.Show("Could not rename collection: " + ex.Message);
                }
            };
            closeTimer = new System.Windows.Forms.Timer();
            closeTimer.Interval = 5000;
            closeTimer.Tick += delegate { this.Close(); };
            closeTimer.Start();
            Rectangle workingArea = Screen.PrimaryScreen.WorkingArea;
            this.Location = new Point(workingArea.Right - this.Width - 10, workingArea.Bottom - this.Height - 10);
            Program.ApplyTheme(this);
        }
    }
    
    public class UploadContext : Form
    {
        private BackgroundWorker worker;
        private UploadOptions opt;
        private string lastItemId = "";
        private bool uploadedCollection = false;
        private string uploadedCollectionId = "";
        private List<string> uploadedItemIds = new List<string>();
        private long batchTotalBytes = 0;
        private long batchCompletedBytes = 0;
        
        private DateTime startTime;
        private NotificationForm progressForm;
        
        public UploadContext(UploadOptions uploadOptions)
        {
            this.FormBorderStyle = FormBorderStyle.None;
            this.ShowInTaskbar = false;
            this.WindowState = FormWindowState.Minimized;
            this.Load += (s, e) => {
                this.Size = new Size(0, 0);
                this.Location = new Point(-10000, -10000);
                this.Hide();
            };

            opt = uploadOptions;
            startTime = DateTime.Now;
            
            worker = new BackgroundWorker();
            worker.WorkerReportsProgress = true;
            worker.DoWork += new DoWorkEventHandler(Worker_DoWork);
            worker.ProgressChanged += new ProgressChangedEventHandler(Worker_ProgressChanged);
            worker.RunWorkerCompleted += new RunWorkerCompletedEventHandler(Worker_RunWorkerCompleted);
            worker.RunWorkerAsync();
        }
        
        private void Worker_DoWork(object sender, DoWorkEventArgs e)
        {
            string[] paths = opt.FilePaths != null && opt.FilePaths.Length > 0 ? opt.FilePaths : new string[] { opt.FilePath };
            foreach (string path in paths) batchTotalBytes += new FileInfo(path).Length;
            if (paths.Length == 1) {
                opt.FilePath = paths[0];
                e.Result = UploadOne();
                if (!string.IsNullOrEmpty(lastItemId)) uploadedItemIds.Add(lastItemId);
                return;
            }

            bool rawCollectionLink = opt.IsRawLink;
            List<string> itemIds = new List<string>();
            for (int i = 0; i < paths.Length; i++) {
                opt.FilePath = paths[i];
                opt.IsRawLink = false;
                opt.CustomId = "";
                opt.CustomFilename = "";
                UploadOne();
                if (string.IsNullOrEmpty(lastItemId)) throw new InvalidDataException("Server did not return an upload ID.");
                itemIds.Add(lastItemId);
                uploadedItemIds.Add(lastItemId);
                batchCompletedBytes += new FileInfo(paths[i]).Length;
                ReportOverallProgress(0);
            }
            Dictionary<string, object> body = new Dictionary<string, object>();
            body["item_ids"] = itemIds.ToArray();
            if (!string.IsNullOrEmpty(opt.Password)) body["password"] = opt.Password;
            string json = new JavaScriptSerializer().Serialize(body);
            HttpWebRequest collectionRequest = Program.CreateRequest(Program.ServerUrl + "/collections/auto", 30000);
            collectionRequest.Method = "POST";
            collectionRequest.ContentType = "application/json";
            collectionRequest.CookieContainer = Program.Cookies;
            collectionRequest.Headers["X-Nullhost-Account"] = Program.AccountId;
            byte[] jsonBytes = Encoding.UTF8.GetBytes(json);
            collectionRequest.ContentLength = jsonBytes.Length;
            using (Stream stream = collectionRequest.GetRequestStream()) stream.Write(jsonBytes, 0, jsonBytes.Length);
            using (WebResponse response = collectionRequest.GetResponse())
            using (StreamReader reader = new StreamReader(response.GetResponseStream())) {
                Dictionary<string, object> payload = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(reader.ReadToEnd());
                if (payload == null || !payload.ContainsKey("url") || !payload.ContainsKey("id")) throw new InvalidDataException("Server returned an invalid collection response.");
                uploadedCollection = true;
                uploadedCollectionId = Convert.ToString(payload["id"]);
                string resultKey = rawCollectionLink && payload.ContainsKey("raw_url") ? "raw_url" : "url";
                e.Result = new Uri(new Uri(Program.ServerUrl), Convert.ToString(payload[resultKey])).ToString();
            }
        }

        private string UploadOne()
        {

            long sourceLength = new FileInfo(opt.FilePath).Length;
            // Use chunked upload for files exceeding 95 MB
            if (sourceLength > 95L * 1024 * 1024) {
                return UploadChunked(sourceLength);
            }
            
            string url = Program.ServerUrl + "/upload";
            string boundary = "---------------------------" + DateTime.Now.Ticks.ToString("x");
            HttpWebRequest request = Program.CreateRequest(url, 5 * 60 * 1000);
            request.ContentType = "multipart/form-data; boundary=" + boundary;
            request.Method = "POST";
            request.KeepAlive = true;
            request.CookieContainer = Program.Cookies;
            if (string.IsNullOrEmpty(Program.AccountId))
                throw new InvalidOperationException("Set your Nullhost account ID in Settings before uploading from the desktop app.");
            request.Headers["X-Nullhost-Account"] = Program.AccountId;
            
            using (MemoryStream headerStream = new MemoryStream())
            {
                WriteFormData(headerStream, boundary, "expiry", Program.DefaultExpiry.ToString());
                WriteFormData(headerStream, boundary, "ajax", "1");
                
                if (!string.IsNullOrEmpty(opt.CustomId)) WriteFormData(headerStream, boundary, "custom_id", opt.CustomId);
                if (!string.IsNullOrEmpty(opt.CustomFilename)) WriteFormData(headerStream, boundary, "custom_filename", opt.CustomFilename);
                if (!string.IsNullOrEmpty(opt.Password)) WriteFormData(headerStream, boundary, "password", opt.Password);
                if (opt.BurnLimit > 0) WriteFormData(headerStream, boundary, "burn_limit", opt.BurnLimit.ToString());
                if (opt.IsPublic) WriteFormData(headerStream, boundary, "is_public", "on");
                WriteFormData(headerStream, boundary, "url_format", opt.UrlFormat);
                
                string fileHeader = "--" + boundary + "\r\n" +
                                "Content-Disposition: form-data; name=\"file\"; filename=\"" + Path.GetFileName(opt.FilePath) + "\"\r\n" +
                                "Content-Type: application/octet-stream\r\n\r\n";
                byte[] fileHeaderBytes = Encoding.UTF8.GetBytes(fileHeader);
                headerStream.Write(fileHeaderBytes, 0, fileHeaderBytes.Length);
                
                byte[] trailer = Encoding.UTF8.GetBytes("\r\n--" + boundary + "--\r\n");
                
                long fileLength = new FileInfo(opt.FilePath).Length;
                request.ContentLength = headerStream.Length + fileLength + trailer.Length;
            request.AllowWriteStreamBuffering = false;
                
                using (Stream requestStream = request.GetRequestStream())
                {
                    byte[] headerArray = headerStream.ToArray();
                    requestStream.Write(headerArray, 0, headerArray.Length);
                    
                    long totalRead = 0;
                    DateTime speedStart = DateTime.Now;
                    using (FileStream fileStream = new FileStream(opt.FilePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                    {
                        byte[] buffer = new byte[65536]; // 64KB chunks
                        int bytesRead = 0;
                        while ((bytesRead = fileStream.Read(buffer, 0, buffer.Length)) != 0)
                        {
                            requestStream.Write(buffer, 0, bytesRead);
                            totalRead += bytesRead;
                            if (fileLength > 0) {
                                double elapsed = (DateTime.Now - speedStart).TotalSeconds;
                                string speedText = elapsed > 0.3 ? FormatSpeed(totalRead / elapsed) : "...";
                                int pct = batchTotalBytes > 0 ? (int)(((batchCompletedBytes + totalRead) * 100L) / batchTotalBytes) : 0;
                                string detail = "Uploading... " + pct + "% (" + speedText + ")";
                                worker.ReportProgress(pct, detail);
                            }
                        }
                    }
                    requestStream.Write(trailer, 0, trailer.Length);
                }
            }
            
            using (WebResponse response = request.GetResponse())
            using (StreamReader reader = new StreamReader(response.GetResponseStream()))
            {
                string result = reader.ReadToEnd();
                
                var payload = new JavaScriptSerializer().Deserialize<System.Collections.Generic.Dictionary<string, object>>(result);
                string responseKey = opt.IsDownloadLink ? "download_url" : (opt.IsRawLink ? "raw_url" : "url");
                if (payload != null && payload.ContainsKey(responseKey)) {
                    lastItemId = payload.ContainsKey("id") ? Convert.ToString(payload["id"]) : "";
                    string path = Convert.ToString(payload[responseKey]);
                    Uri baseUri = new Uri(Program.ServerUrl);
                    string root = baseUri.Scheme + "://" + baseUri.Host + (baseUri.IsDefaultPort ? "" : ":" + baseUri.Port);
                    return root + path;
                } else {
                    throw new InvalidDataException("Server returned an invalid upload response.");
                }
            }
        }

        private string UploadChunked(long fileLength)
        {
            const int chunkSize = 95 * 1024 * 1024;
            int totalChunks = (int)Math.Ceiling(fileLength / (double)chunkSize);
            string uploadId = Guid.NewGuid().ToString("N");
            string finalResult = "";
            DateTime speedStart = DateTime.Now;
            long uploadedBytesTracker = 0;

            using (FileStream source = new FileStream(opt.FilePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            {
                for (int index = 0; index < totalChunks; index++)
                {
                    int wanted = (int)Math.Min(chunkSize, fileLength - source.Position);
                    byte[] chunk = new byte[wanted];
                    int offset = 0;
                    while (offset < wanted) {
                        int read = source.Read(chunk, offset, wanted - offset);
                        if (read <= 0) throw new EndOfStreamException("The selected file changed during upload.");
                        offset += read;
                    }

                    string boundary = "---------------------------" + Guid.NewGuid().ToString("N");
                    HttpWebRequest request = Program.CreateRequest(Program.ServerUrl + "/upload_chunk", 10 * 60 * 1000);
                    request.Method = "POST";
                    request.ContentType = "multipart/form-data; boundary=" + boundary;
                    request.CookieContainer = Program.Cookies;
                    request.Headers["X-Nullhost-Account"] = Program.AccountId;

                    using (MemoryStream body = new MemoryStream())
                    {
                        WriteFormData(body, boundary, "upload_id", uploadId);
                        WriteFormData(body, boundary, "chunk_index", index.ToString());
                        WriteFormData(body, boundary, "total_chunks", totalChunks.ToString());
                        WriteFormData(body, boundary, "filename", Path.GetFileName(opt.FilePath));
                        WriteFormData(body, boundary, "mimetype", "application/octet-stream");
                        WriteFormData(body, boundary, "expiry", Program.DefaultExpiry.ToString());
                        WriteFormData(body, boundary, "url_format", opt.UrlFormat);
                        if (!string.IsNullOrEmpty(opt.CustomId)) WriteFormData(body, boundary, "custom_id", opt.CustomId);
                        if (!string.IsNullOrEmpty(opt.CustomFilename)) WriteFormData(body, boundary, "custom_filename", opt.CustomFilename);
                        if (!string.IsNullOrEmpty(opt.Password)) WriteFormData(body, boundary, "password", opt.Password);
                        if (opt.BurnLimit > 0) WriteFormData(body, boundary, "burn_limit", opt.BurnLimit.ToString());
                        if (opt.IsPublic) WriteFormData(body, boundary, "is_public", "on");

                        byte[] fileHeader = Encoding.UTF8.GetBytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n");
                        body.Write(fileHeader, 0, fileHeader.Length);
                        body.Write(chunk, 0, chunk.Length);
                        byte[] trailer = Encoding.UTF8.GetBytes("\r\n--" + boundary + "--\r\n");
                        body.Write(trailer, 0, trailer.Length);
                        request.ContentLength = body.Length;
                        body.Position = 0;
                        using (Stream target = request.GetRequestStream())
                        {
                            byte[] buffer = new byte[65536];
                            long bytesSentInChunk = 0;
                            int readBytes;
                            while ((readBytes = body.Read(buffer, 0, buffer.Length)) > 0)
                            {
                                target.Write(buffer, 0, readBytes);
                                bytesSentInChunk += readBytes;
                                uploadedBytesTracker += readBytes;

                                double elapsed = (DateTime.Now - speedStart).TotalSeconds;
                                string speedText = elapsed > 0.3 ? FormatSpeed(uploadedBytesTracker / elapsed) : "...";
                                int chunksLeft = totalChunks - (index + 1);
                                string chunkInfo = "chunk " + (index + 1) + "/" + totalChunks + " (" + chunksLeft + " left)";

                                long overallUploaded = Math.Min(batchTotalBytes, batchCompletedBytes + (long)index * chunkSize + (long)(bytesSentInChunk * (double)wanted / body.Length));
                                int pct = batchTotalBytes > 0 ? (int)((overallUploaded * 100L) / batchTotalBytes) : 0;
                                string statusDetail = "Uploading... " + pct + "% (" + speedText + ") · " + chunkInfo;
                                worker.ReportProgress(pct, statusDetail);
                            }
                        }
                    }

                    using (WebResponse response = request.GetResponse())
                    using (StreamReader reader = new StreamReader(response.GetResponseStream()))
                        finalResult = reader.ReadToEnd();

                    try
                    {
                        var earlyPayload = new JavaScriptSerializer().Deserialize<System.Collections.Generic.Dictionary<string, object>>(finalResult);
                        if (earlyPayload != null && earlyPayload.ContainsKey("id"))
                            lastItemId = Convert.ToString(earlyPayload["id"]);
                    }
                    catch { }

                    ReportOverallProgress(source.Position);
                }
            }

            var payload = new JavaScriptSerializer().Deserialize<System.Collections.Generic.Dictionary<string, object>>(finalResult);
            string key = opt.IsDownloadLink ? "download_url" : (opt.IsRawLink ? "raw_url" : "url");
            if (payload == null || !payload.ContainsKey(key)) throw new InvalidDataException("Server returned an invalid upload response.");
            lastItemId = payload.ContainsKey("id") ? Convert.ToString(payload["id"]) : "";
            Uri baseUri = new Uri(Program.ServerUrl);
            string root = baseUri.Scheme + "://" + baseUri.Host + (baseUri.IsDefaultPort ? "" : ":" + baseUri.Port);
            return root + Convert.ToString(payload[key]);
        }

        private static string FormatSpeed(double bytesPerSec)
        {
            if (bytesPerSec <= 0) return "0 B/s";
            if (bytesPerSec < 1024) return bytesPerSec.ToString("0") + " B/s";
            if (bytesPerSec < 1024 * 1024) return (bytesPerSec / 1024.0).ToString("0.0") + " KB/s";
            if (bytesPerSec < 1024 * 1024 * 1024) return (bytesPerSec / (1024.0 * 1024.0)).ToString("0.0") + " MB/s";
            return (bytesPerSec / (1024.0 * 1024.0 * 1024.0)).ToString("0.00") + " GB/s";
        }

        private void ReportOverallProgress(long currentFileBytes, string details = null)
        {
            if (batchTotalBytes <= 0) return;
            long uploaded = Math.Min(batchTotalBytes, batchCompletedBytes + Math.Max(0, currentFileBytes));
            int pct = (int)((uploaded * 100L) / batchTotalBytes);
            worker.ReportProgress(pct, string.IsNullOrEmpty(details) ? ("Uploading... " + pct + "%") : details);
        }
        
        private void WriteFormData(Stream stream, string boundary, string name, string value)
        {
            string data = "--" + boundary + "\r\n" +
                          "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" +
                          value + "\r\n";
            byte[] bytes = Encoding.UTF8.GetBytes(data);
            stream.Write(bytes, 0, bytes.Length);
        }
        
        private void Worker_ProgressChanged(object sender, ProgressChangedEventArgs e)
        {
            if ((DateTime.Now - startTime).TotalSeconds > 1)
            {
                if (progressForm == null)
                {
                    progressForm = new NotificationForm(0);
                    progressForm.Show(); 
                }
                progressForm.UpdateProgress(e.ProgressPercentage, Convert.ToString(e.UserState));
            }
        }
        
        private void Worker_RunWorkerCompleted(object sender, RunWorkerCompletedEventArgs e)
        {
            if (progressForm != null) {
                progressForm.Close();
                progressForm.Dispose();
                progressForm = null;
            }

            if (e.Error != null || e.Result == null || string.IsNullOrEmpty(e.Result.ToString()))
            {
                using (NotificationForm nf = new NotificationForm(2, e.Error != null ? e.Error.Message : "Upload failed!")) { nf.ShowDialog(); }
            }
            else
            {
                Program.SetClipboardText(e.Result.ToString());
                if (uploadedCollection) {
                    using (CollectionNotificationForm nf = new CollectionNotificationForm(uploadedCollectionId, e.Result.ToString(), uploadedItemIds.ToArray())) { nf.ShowDialog(); }
                } else {
                    using (NotificationForm nf = new NotificationForm(1, "", e.Result.ToString(), uploadedItemIds.ToArray())) { nf.ShowDialog(); }
                }
            }
            
            Environment.Exit(0);
        }
    }
}
