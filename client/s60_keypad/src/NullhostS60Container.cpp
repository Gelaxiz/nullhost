#include "NullhostS60Container.h"
#include <eikenv.h>
#include <gdi.h>

CNullhostS60Container* CNullhostS60Container::NewL(const TRect& aRect)
    {
    CNullhostS60Container* self = new (ELeave) CNullhostS60Container();
    CleanupStack::PushL(self);
    self->ConstructL(aRect);
    CleanupStack::Pop(self);
    return self;
    }

CNullhostS60Container::CNullhostS60Container()
    : iCurrentTab(ETabUpload),
      iBufferBitmap(NULL),
      iBufferDevice(NULL),
      iBufferGc(NULL),
      iFileBrowser(NULL),
      iGalleryScanner(NULL),
      iHttpEngine(NULL),
      iSelectedFileSize(0),
      iCursorUpload(0),
      iCursorBrowse(0),
      iCursorGallery(0),
      iViewerScroll(0),
      iViewerContent(NULL)
    {
    iServerUrl.Copy(_L("https://nullhost.gelaxiz.fi"));
    iExpiry.Copy(_L("24h"));
    iBurnViews.Copy(_L("0"));
    iCurrentBrowserPath.Copy(_L("C:\\"));
    }

void CNullhostS60Container::ConstructL(const TRect& aRect)
    {
    CreateWindowL();
    SetRect(aRect);

    InitDoubleBufferL();

    iFileBrowser = CNullhostFileBrowser::NewL();
    iGalleryScanner = CNullhostGalleryScanner::NewL();
    iHttpEngine = CNullhostHttpEngine::NewL(*this);

    ActivateL();
    }

CNullhostS60Container::~CNullhostS60Container()
    {
    delete iBufferGc;
    delete iBufferDevice;
    delete iBufferBitmap;
    delete iFileBrowser;
    delete iGalleryScanner;
    delete iHttpEngine;
    delete iViewerContent;
    iBrowserEntries.Close();
    iGalleryEntries.Close();
    }

void CNullhostS60Container::InitDoubleBufferL()
    {
    delete iBufferGc; iBufferGc = NULL;
    delete iBufferDevice; iBufferDevice = NULL;
    delete iBufferBitmap; iBufferBitmap = NULL;

    TSize sz = Rect().Size();
    if (sz.iWidth <= 0 || sz.iHeight <= 0) return;

    iBufferBitmap = new (ELeave) CFbsBitmap();
    User::LeaveIfError(iBufferBitmap->Create(sz, EColor16MA));

    iBufferDevice = CFbsBitmapDevice::NewL(iBufferBitmap);
    User::LeaveIfError(iBufferDevice->CreateContext(iBufferGc));
    }

void CNullhostS60Container::SizeChanged()
    {
    TRAP_IGNORE(InitDoubleBufferL());
    DrawNow();
    }

void CNullhostS60Container::SetTab(TNullhostS60Tab aTab)
    {
    iCurrentTab = aTab;
    if (aTab == ETabBrowse && iBrowserEntries.Count() == 0)
        {
        TRAP_IGNORE(iFileBrowser->ReadDirectoryL(iCurrentBrowserPath, iBrowserEntries));
        }
    else if (aTab == ETabGallery && iGalleryEntries.Count() == 0)
        {
        TRAP_IGNORE(iGalleryScanner->ScanImagesL(iGalleryEntries));
        }
    DrawNow();
    }

void CNullhostS60Container::SetSelectedFile(const TDesC& aPath, TInt64 aSize)
    {
    iSelectedFile.Copy(aPath);
    iSelectedFileSize = aSize;
    iCurrentTab = ETabUpload;
    DrawNow();
    }

void CNullhostS60Container::StartUpload()
    {
    if (iSelectedFile.Length() == 0) return;
    TRAP_IGNORE(iHttpEngine->AuthenticateAndUploadL(
        iServerUrl, iSelectedFile, iExpiry, iBurnViews, iPassword, iCustomSlug, iAccountId));
    }

void CNullhostS60Container::SetAccount(const TDesC& aAccountId)
    {
    iAccountId.Copy(aAccountId);
    DrawNow();
    }

void CNullhostS60Container::ShowViewer(const TDesC& aTitle, const TDesC& aContent)
    {
    iViewerTitle.Copy(aTitle);
    delete iViewerContent;
    iViewerContent = NULL;
    TRAP_IGNORE(iViewerContent = aContent.AllocL());
    iCurrentTab = ETabViewer;
    iViewerScroll = 0;
    DrawNow();
    }

// ----------------------------------------------------------------------------
// HARDWARE KEYPAD NAVIGATION
// ----------------------------------------------------------------------------
TKeyResponse CNullhostS60Container::OfferKeyEventL(const TKeyEvent& aKeyEvent, TEventCode aType)
    {
    if (aType != EEventKey) return EKeyWasNotConsumed;

    TUint code = aKeyEvent.iCode;

    // Number key shortcuts 1-4 for fast tab switching
    if (code >= '1' && code <= '4')
        {
        SetTab((TNullhostS60Tab)(code - '1'));
        return EKeyWasConsumed;
        }

    switch (code)
        {
        case EKeyLeftArrow:
            if (iCurrentTab != ETabViewer)
                {
                TInt prev = (TInt)iCurrentTab - 1;
                if (prev < 0) prev = (TInt)ETabAccount;
                SetTab((TNullhostS60Tab)prev);
                return EKeyWasConsumed;
                }
            break;

        case EKeyRightArrow:
            if (iCurrentTab != ETabViewer)
                {
                TInt next = ((TInt)iCurrentTab + 1) % 4;
                SetTab((TNullhostS60Tab)next);
                return EKeyWasConsumed;
                }
            break;

        case EKeyUpArrow:
            if (iCurrentTab == ETabUpload)
                iCursorUpload = Max(0, iCursorUpload - 1);
            else if (iCurrentTab == ETabBrowse)
                iCursorBrowse = Max(0, iCursorBrowse - 1);
            else if (iCurrentTab == ETabGallery)
                iCursorGallery = Max(0, iCursorGallery - 1);
            else if (iCurrentTab == ETabViewer)
                iViewerScroll = Max(0, iViewerScroll - 1);
            DrawNow();
            return EKeyWasConsumed;

        case EKeyDownArrow:
            if (iCurrentTab == ETabUpload)
                iCursorUpload = Min(5, iCursorUpload + 1);
            else if (iCurrentTab == ETabBrowse)
                iCursorBrowse = Min(Max(0, iBrowserEntries.Count() - 1), iCursorBrowse + 1);
            else if (iCurrentTab == ETabGallery)
                iCursorGallery = Min(Max(0, iGalleryEntries.Count() - 1), iCursorGallery + 1);
            else if (iCurrentTab == ETabViewer)
                iViewerScroll++;
            DrawNow();
            return EKeyWasConsumed;

        case EKeyDevice3: // Center D-Pad Select key
            if (iCurrentTab == ETabUpload)
                HandleUploadSelectL();
            else if (iCurrentTab == ETabBrowse)
                HandleBrowseSelectL();
            else if (iCurrentTab == ETabGallery)
                HandleGallerySelectL();
            else if (iCurrentTab == ETabViewer)
                SetTab(ETabUpload);
            return EKeyWasConsumed;

        default:
            break;
        }

    return EKeyWasNotConsumed;
    }

void CNullhostS60Container::HandleUploadSelectL()
    {
    switch (iCursorUpload)
        {
        case 0: // Select file
            SetTab(ETabBrowse);
            break;
        case 1: // Expiry toggle
            if (iExpiry.Compare(_L("1h")) == 0) iExpiry.Copy(_L("24h"));
            else if (iExpiry.Compare(_L("24h")) == 0) iExpiry.Copy(_L("7d"));
            else if (iExpiry.Compare(_L("7d")) == 0) iExpiry.Copy(_L("30d"));
            else if (iExpiry.Compare(_L("30d")) == 0) iExpiry.Copy(_L("never"));
            else iExpiry.Copy(_L("1h"));
            DrawNow();
            break;
        case 2: // Burn toggle
            if (iBurnViews.Compare(_L("0")) == 0) iBurnViews.Copy(_L("1"));
            else if (iBurnViews.Compare(_L("1")) == 0) iBurnViews.Copy(_L("5"));
            else if (iBurnViews.Compare(_L("5")) == 0) iBurnViews.Copy(_L("10"));
            else iBurnViews.Copy(_L("0"));
            DrawNow();
            break;
        case 5: // Upload action
            StartUpload();
            break;
        default:
            break;
        }
    }

void CNullhostS60Container::HandleBrowseSelectL()
    {
    if (iCursorBrowse >= 0 && iCursorBrowse < iBrowserEntries.Count())
        {
        const TNullhostFileEntry& e = iBrowserEntries[iCursorBrowse];
        if (e.iIsDir)
            {
            iCurrentBrowserPath.Copy(e.iFullPath);
            iCursorBrowse = 0;
            iFileBrowser->ReadDirectoryL(iCurrentBrowserPath, iBrowserEntries);
            DrawNow();
            }
        else
            {
            SetSelectedFile(e.iFullPath, e.iSize);
            }
        }
    }

void CNullhostS60Container::HandleGallerySelectL()
    {
    if (iCursorGallery >= 0 && iCursorGallery < iGalleryEntries.Count())
        {
        const TNullhostFileEntry& e = iGalleryEntries[iCursorGallery];
        SetSelectedFile(e.iFullPath, e.iSize);
        }
    }

// ----------------------------------------------------------------------------
// MNullhostHttpObserver
// ----------------------------------------------------------------------------
void CNullhostS60Container::OnUploadProgress(TInt /*aBytesSent*/, TInt /*aTotalBytes*/)
    {
    DrawNow();
    }

void CNullhostS60Container::OnUploadSuccess(const TDesC& aUrl, const TDesC& /*aRawUrl*/, const TDesC& /*aDownloadUrl*/)
    {
    iResultUrl.Copy(aUrl);
    TBuf<256> msg;
    msg.Format(_L("File Uploaded Successfully!\nLink: %S\nSaved to clipboard."), &aUrl);
    ShowViewer(_L("Upload Succeeded"), msg);
    }

void CNullhostS60Container::OnUploadError(TInt /*aErrorCode*/, const TDesC& aErrorMessage)
    {
    ShowViewer(_L("Upload Failed"), aErrorMessage);
    }

// ----------------------------------------------------------------------------
// S60 DRAWING
// ----------------------------------------------------------------------------
void CNullhostS60Container::Draw(const TRect& aRect) const
    {
    if (!iBufferGc) return;

    iBufferGc->SetBrushColor(TRgb(20, 24, 30));
    iBufferGc->Clear(Rect());

    DrawHeader(*iBufferGc);

    if (iCurrentTab != ETabViewer)
        DrawTabs(*iBufferGc);

    switch (iCurrentTab)
        {
        case ETabUpload:
            DrawTabUpload(*iBufferGc);
            break;
        case ETabBrowse:
            DrawTabBrowse(*iBufferGc);
            break;
        case ETabGallery:
            DrawTabGallery(*iBufferGc);
            break;
        case ETabAccount:
            DrawTabAccount(*iBufferGc);
            break;
        case ETabViewer:
            DrawViewer(*iBufferGc);
            break;
        default:
            break;
        }

    CWindowGc& gc = SystemGc();
    gc.BitBlt(aRect.iTl, iBufferBitmap, aRect);
    }

void CNullhostS60Container::DrawHeader(CBitmapContext& aGc) const
    {
    aGc.SetBrushColor(TRgb(12, 16, 22));
    aGc.SetPenColor(TRgb(44, 52, 68));
    aGc.DrawRect(TRect(0, 0, Rect().Width(), 22));

    const CFont* font = CEikonEnv::Static()->TitleFont();
    aGc.UseFont(font);
    aGc.SetPenColor(TRgb(0, 180, 255));
    aGc.DrawText(_L("Nullhost S60"), TPoint(4, 16));

    TTime now; now.HomeTime();
    TBuf<16> timeStr;
    now.FormatL(timeStr, _L("%H:%T"));
    aGc.SetPenColor(TRgb(160, 172, 188));
    aGc.DrawText(timeStr, TPoint(Rect().Width() - 44, 16));
    aGc.DiscardFont();
    }

void CNullhostS60Container::DrawTabs(CBitmapContext& aGc) const
    {
    TInt tabW = Rect().Width() / 4;
    const CFont* font = CEikonEnv::Static()->DenseFont();
    aGc.UseFont(font);

    const TText* tabs[4] = { _S("Upload"), _S("Browse"), _S("Gallery"), _S("Account") };
    for (TInt i = 0; i < 4; i++)
        {
        TInt tx = i * tabW;
        TBool active = (i == (TInt)iCurrentTab);
        aGc.SetBrushColor(active ? TRgb(0, 114, 206) : TRgb(28, 34, 44));
        aGc.SetPenColor(TRgb(44, 52, 68));
        aGc.DrawRect(TRect(tx, 22, tx + tabW, 42));

        aGc.SetPenColor(TRgb(255, 255, 255));
        TPtrC tName(tabs[i]);
        aGc.DrawText(tName, TPoint(tx + 4, 36));
        }
    aGc.DiscardFont();
    }

void CNullhostS60Container::DrawTabUpload(CBitmapContext& aGc) const
    {
    const CFont* font = CEikonEnv::Static()->DenseFont();
    aGc.UseFont(font);

    const TText* labels[6] = 
        {
        _S("File:"),
        _S("Expiry:"),
        _S("Burn Views:"),
        _S("Password:"),
        _S("Custom Slug:"),
        _S(">>> UPLOAD FILE NOW <<<")
        };

    TInt y = 48;
    TInt rowH = 26;
    for (TInt i = 0; i < 6; i++)
        {
        TBool isCursor = (i == iCursorUpload);
        aGc.SetBrushColor(isCursor ? TRgb(0, 102, 204) : (i % 2 == 0 ? TRgb(24, 28, 36) : TRgb(30, 36, 46)));
        aGc.SetPenColor(TRgb(44, 52, 68));
        aGc.DrawRect(TRect(2, y, Rect().Width() - 2, y + rowH));

        if (i == 5)
            {
            aGc.SetPenColor(isCursor ? TRgb(255, 255, 0) : TRgb(255, 255, 255));
            TPtrC action(labels[i]);
            aGc.DrawText(action, TPoint(16, y + 17));
            }
        else
            {
            aGc.SetPenColor(isCursor ? TRgb(255, 255, 255) : TRgb(0, 180, 255));
            TPtrC lbl(labels[i]);
            aGc.DrawText(lbl, TPoint(6, y + 17));

            aGc.SetPenColor(TRgb(255, 255, 255));
            if (i == 0)
                {
                if (iSelectedFile.Length() > 0)
                    {
                    TParse p; p.Set(iSelectedFile, NULL, NULL);
                    aGc.DrawText(p.NameAndExt(), TPoint(90, y + 17));
                    }
                else
                    aGc.DrawText(_L("(None)"), TPoint(90, y + 17));
                }
            else if (i == 1)
                aGc.DrawText(iExpiry, TPoint(90, y + 17));
            else if (i == 2)
                {
                if (iBurnViews.Compare(_L("0")) == 0) aGc.DrawText(_L("Off"), TPoint(90, y + 17));
                else aGc.DrawText(iBurnViews, TPoint(90, y + 17));
                }
            }
        y += rowH + 2;
        }

    aGc.SetPenColor(TRgb(160, 172, 188));
    aGc.DrawText(_L("D-Pad: Navigate | Center: Select"), TPoint(4, Rect().Height() - 4));
    aGc.DiscardFont();
    }

void CNullhostS60Container::DrawTabBrowse(CBitmapContext& aGc) const
    {
    const CFont* font = CEikonEnv::Static()->DenseFont();
    aGc.UseFont(font);

    aGc.SetPenColor(TRgb(0, 180, 255));
    aGc.DrawText(iCurrentBrowserPath, TPoint(4, 54));

    TInt y = 58;
    TInt rowH = 22;
    for (TInt i = 0; i < iBrowserEntries.Count(); i++)
        {
        if (y < Rect().Height() - 20)
            {
            TBool isCursor = (i == iCursorBrowse);
            aGc.SetBrushColor(isCursor ? TRgb(0, 102, 204) : (i % 2 == 0 ? TRgb(24, 28, 36) : TRgb(30, 36, 46)));
            aGc.SetPenColor(TRgb(44, 52, 68));
            aGc.DrawRect(TRect(2, y, Rect().Width() - 2, y + rowH));

            TBuf<64> disp;
            if (iBrowserEntries[i].iIsDir) disp.Format(_L("[*] %S"), &iBrowserEntries[i].iName);
            else disp.Format(_L("[ ] %S"), &iBrowserEntries[i].iName);

            aGc.SetPenColor(TRgb(255, 255, 255));
            aGc.DrawText(disp, TPoint(4, y + 16));
            }
        y += rowH + 1;
        }
    aGc.DiscardFont();
    }

void CNullhostS60Container::DrawTabGallery(CBitmapContext& aGc) const
    {
    const CFont* font = CEikonEnv::Static()->DenseFont();
    aGc.UseFont(font);

    aGc.SetPenColor(TRgb(0, 180, 255));
    aGc.DrawText(_L("Device Photos Scan:"), TPoint(4, 54));

    TInt y = 58;
    TInt rowH = 22;
    for (TInt i = 0; i < iGalleryEntries.Count(); i++)
        {
        if (y < Rect().Height() - 20)
            {
            TBool isCursor = (i == iCursorGallery);
            aGc.SetBrushColor(isCursor ? TRgb(0, 102, 204) : (i % 2 == 0 ? TRgb(24, 28, 36) : TRgb(30, 36, 46)));
            aGc.SetPenColor(TRgb(44, 52, 68));
            aGc.DrawRect(TRect(2, y, Rect().Width() - 2, y + rowH));

            aGc.SetPenColor(TRgb(255, 255, 255));
            aGc.DrawText(iGalleryEntries[i].iName, TPoint(4, y + 16));
            }
        y += rowH + 1;
        }
    aGc.DiscardFont();
    }

void CNullhostS60Container::DrawTabAccount(CBitmapContext& aGc) const
    {
    const CFont* titleFont = CEikonEnv::Static()->TitleFont();
    const CFont* normFont = CEikonEnv::Static()->NormalFont();

    aGc.UseFont(titleFont);
    aGc.SetPenColor(TRgb(0, 180, 255));
    aGc.DrawText(_L("Account Settings"), TPoint(6, 58));
    aGc.DiscardFont();

    aGc.UseFont(normFont);
    aGc.SetPenColor(TRgb(160, 172, 188));
    aGc.DrawText(_L("Account ID (24-digit):"), TPoint(6, 82));

    aGc.SetPenColor(TRgb(255, 255, 255));
    if (iAccountId.Length() > 0) aGc.DrawText(iAccountId, TPoint(6, 104));
    else aGc.DrawText(_L("(Not configured)"), TPoint(6, 104));

    aGc.SetPenColor(TRgb(160, 172, 188));
    aGc.DrawText(_L("Server URL:"), TPoint(6, 140));
    aGc.SetPenColor(TRgb(255, 255, 255));
    aGc.DrawText(iServerUrl, TPoint(6, 160));
    aGc.DiscardFont();
    }

void CNullhostS60Container::DrawViewer(CBitmapContext& aGc) const
    {
    const CFont* font = CEikonEnv::Static()->DenseFont();
    aGc.UseFont(font);

    aGc.SetPenColor(TRgb(0, 180, 255));
    aGc.DrawText(iViewerTitle, TPoint(4, 38));

    if (iViewerContent)
        {
        aGc.SetPenColor(TRgb(255, 255, 255));
        aGc.DrawText(*iViewerContent, TPoint(4, 58));
        }

    aGc.SetPenColor(TRgb(160, 172, 188));
    aGc.DrawText(_L("Press Select or Back to return"), TPoint(4, Rect().Height() - 4));
    aGc.DiscardFont();
    }
