# Nullhost Windows Desktop Client

Native Windows desktop client written in C# (.NET Framework 4.5+). Provides background system tray integration and Windows Explorer shell context menu integration.

## Features
- **System Tray Integration**: Stays quietly in the notification area.
- **Windows Explorer Context Menu**: Right-click any file and select **"Upload to Nullhost"**.
- **Chunked Uploads**: Automatically splits large files (>95 MB) into chunks for reliable upload through reverse proxies.
- **Account Binding**: Configure 24-digit Account ID to track all desktop uploads in your private account dashboard.
- **Clipboard Notification**: Automatically copies generated short links to clipboard upon upload completion.

## Building from Source
Using standard .NET Framework compiler (`csc.exe`):
```cmd
build.bat
```
Or manually:
```cmd
C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe /target:winexe /win32icon:logo.ico /out:Nullhost.exe /reference:System.Windows.Forms.dll,System.Drawing.dll,System.Net.Http.dll,System.IO.Compression.FileSystem.dll NullhostClient.cs
```
Output: `Nullhost.exe`
