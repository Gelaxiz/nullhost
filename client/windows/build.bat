@echo off
echo Building Nullhost Desktop Client for Windows...
set CSC="C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
if not exist %CSC% (
    set CSC="C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
)
if not exist %CSC% (
    echo Error: .NET Framework 4.0/4.5 compiler (csc.exe) not found.
    pause
    exit /b 1
)

%CSC% /target:winexe /win32icon:logo.ico /out:Nullhost.exe /reference:System.Windows.Forms.dll,System.Drawing.dll,System.Net.Http.dll,System.IO.Compression.FileSystem.dll NullhostClient.cs

if exist Nullhost.exe (
    echo Build successful! Generated Nullhost.exe
) else (
    echo Build failed.
)
pause
