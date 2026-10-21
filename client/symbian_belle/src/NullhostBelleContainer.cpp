#include "NullhostBelleContainer.h"
#include <eikenv.h>
#include <gdi.h>

CNullhostBelleContainer* CNullhostBelleContainer::NewL(const TRect& aRect)
    {
    CNullhostBelleContainer* self = new (ELeave) CNullhostBelleContainer();
    CleanupStack::PushL(self);
    self->ConstructL(aRect);
    CleanupStack::Pop(self);
    return self;
    }

CNullhostBelleContainer::CNullhostBelleContainer()
    : iCurrentView(ENullhostViewUpload),
      iBufferBitmap(NULL),
      iBufferDevice(NULL),
      iBufferGc(NULL),
      iFileBrowser(NULL),
      iGalleryScanner(NULL),
      iHttpEngine(NULL),
      iSelectedFileSize(0),
      iScrollOffset(0),
      iTextContent(NULL)
    {
    iServerUrl.Copy(_L("https://nullhost.gelaxiz.fi"));
    iExpiry.Copy(_L("24h"));
    iBurnViews.Copy(_L("0"));
    iCurrentBrowserPath.Copy(_L("C:\\"));
    }

void CNullhostBelleContainer::ConstructL(const TRect& aRect)
    {
    CreateWindowL();
    SetRect(aRect);

    InitDoubleBufferL();

    iFileBrowser = CNullhostFileBrowser::NewL();
    iGalleryScanner = CNullhostGalleryScanner::NewL();
    iHttpEngine = CNullhostHttpEngine::NewL(*this);

    EnableDragEvents();
    ActivateL();
    }

CNullhostBelleContainer::~CNullhostBelleContainer()
    {
    delete iBufferGc;
    delete iBufferDevice;
    delete iBufferBitmap;
    delete iFileBrowser;
    delete iGalleryScanner;
    delete iHttpEngine;
    delete iTextContent;
    iBrowserEntries.Close();
    iGalleryEntries.Close();
    }

void CNullhostBelleContainer::InitDoubleBufferL()
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

void CNullhostBelleContainer::SizeChanged()
    {
    TRAP_IGNORE(InitDoubleBufferL());
    DrawNow();
    }

void CNullhostBelleContainer::SetView(TNullhostViewType aView)
    {
    iCurrentView = aView;
    iScrollOffset = 0;
    if (aView == ENullhostViewBrowser)
        {
        TRAP_IGNORE(iFileBrowser->ReadDirectoryL(iCurrentBrowserPath, iBrowserEntries));
        }
    else if (aView == ENullhostViewGallery)
        {
        TRAP_IGNORE(iGalleryScanner->ScanImagesL(iGalleryEntries));
        }
    DrawNow();
    }

void CNullhostBelleContainer::SetSelectedFile(const TDesC& aPath, TInt64 aSize)
    {
    iSelectedFile.Copy(aPath);
    iSelectedFileSize = aSize;
    iCurrentView = ENullhostViewUpload;
    DrawNow();
    }

void CNullhostBelleContainer::StartUpload()
    {
    if (iSelectedFile.Length() == 0) return;
    TRAP_IGNORE(iHttpEngine->AuthenticateAndUploadL(
        iServerUrl, iSelectedFile, iExpiry, iBurnViews, iPassword, iCustomSlug, iAccountId));
    }

void CNullhostBelleContainer::SetAccount(const TDesC& aAccountId)
    {
    iAccountId.Copy(aAccountId);
    DrawNow();
    }

void CNullhostBelleContainer::ShowTextScreen(const TDesC& aTitle, const TDesC& aContent)
    {
    iTextTitle.Copy(aTitle);
    delete iTextContent;
    iTextContent = NULL;
    TRAP_IGNORE(iTextContent = aContent.AllocL());
    iCurrentView = ENullhostViewText;
    iScrollOffset = 0;
    DrawNow();
    }

// ----------------------------------------------------------------------------
// TOUCH EVENT HANDLING
// ----------------------------------------------------------------------------
void CNullhostBelleContainer::HandlePointerEventL(const TPointerEvent& aPointerEvent)
    {
    if (aPointerEvent.iType == TPointerEvent::EButton1Up)
        {
        TPoint pt = aPointerEvent.iPosition;
        TInt h = Rect().Height();

        // Check Belle FP2 Bottom Toolbar (bottom 56 pixels)
        if (pt.iY >= (h - 56))
            {
            HandleToolbarTouch(pt);
            return;
            }

        // View specific touch dispatch
        switch (iCurrentView)
            {
            case ENullhostViewUpload:
                HandleUploadTouch(pt);
                break;
            case ENullhostViewGallery:
                HandleGalleryTouch(pt);
                break;
            case ENullhostViewBrowser:
                HandleBrowserTouch(pt);
                break;
            case ENullhostViewResult:
                HandleResultTouch(pt);
                break;
            case ENullhostViewText:
                if (pt.iY < 48) SetView(ENullhostViewUpload);
                break;
            default:
                break;
            }
        }
    else if (aPointerEvent.iType == TPointerEvent::EDrag)
        {
        // Smooth scrolling in lists and text view
        iScrollOffset = Max(0, iScrollOffset + (aPointerEvent.iParentPosition.iY - aPointerEvent.iPosition.iY));
        DrawNow();
        }
    }

void CNullhostBelleContainer::HandleToolbarTouch(const TPoint& aPoint)
    {
    TInt w = Rect().Width();
    TInt btnWidth = w / 4;
    TInt idx = aPoint.iX / btnWidth;

    if (idx == 0) // Options
        {
        // Triggers native Avkon options menu
        CEikonEnv::Static()->AppUiFactory()->MenuBar()->TryDisplayMenuBarL();
        }
    else if (idx == 1) // Gallery
        {
        SetView(ENullhostViewGallery);
        }
    else if (idx == 2) // Browse
        {
        SetView(ENullhostViewBrowser);
        }
    else if (idx == 3) // Upload action
        {
        if (iCurrentView == ENullhostViewUpload)
            {
            if (iSelectedFile.Length() > 0) StartUpload();
            else SetView(ENullhostViewBrowser);
            }
        else
            {
            SetView(ENullhostViewUpload);
            }
        }
    }

void CNullhostBelleContainer::HandleUploadTouch(const TPoint& aPoint)
    {
    TInt w = Rect().Width();
    // Expiry pill: y 190-230, x < w/2
    if (aPoint.iY >= 190 && aPoint.iY <= 230)
        {
        if (aPoint.iX < w / 2)
            {
            if (iExpiry.Compare(_L("1h")) == 0) iExpiry.Copy(_L("24h"));
            else if (iExpiry.Compare(_L("24h")) == 0) iExpiry.Copy(_L("7d"));
            else if (iExpiry.Compare(_L("7d")) == 0) iExpiry.Copy(_L("30d"));
            else if (iExpiry.Compare(_L("30d")) == 0) iExpiry.Copy(_L("never"));
            else iExpiry.Copy(_L("1h"));
            DrawNow();
            }
        else
            {
            if (iBurnViews.Compare(_L("0")) == 0) iBurnViews.Copy(_L("1"));
            else if (iBurnViews.Compare(_L("1")) == 0) iBurnViews.Copy(_L("5"));
            else if (iBurnViews.Compare(_L("5")) == 0) iBurnViews.Copy(_L("10"));
            else iBurnViews.Copy(_L("0"));
            DrawNow();
            }
        }
    // Upload Action Button: y 295-350
    else if (aPoint.iY >= 295 && aPoint.iY <= 350)
        {
        if (iSelectedFile.Length() > 0) StartUpload();
        else SetView(ENullhostViewBrowser);
        }
    // Fast buttons: System Pick, Explorer, Gallery: y 365-405
    else if (aPoint.iY >= 365 && aPoint.iY <= 405)
        {
        TInt colW = w / 3;
        TInt cIdx = aPoint.iX / colW;
        if (cIdx == 0) SetView(ENullhostViewBrowser);
        else if (cIdx == 1) SetView(ENullhostViewBrowser);
        else SetView(ENullhostViewGallery);
        }
    }

void CNullhostBelleContainer::HandleGalleryTouch(const TPoint& aPoint)
    {
    if (aPoint.iY < 48)
        {
        SetView(ENullhostViewUpload);
        return;
        }
    TInt rowHeight = 56;
    TInt clickedIdx = (aPoint.iY - 48 + iScrollOffset) / rowHeight;
    if (clickedIdx >= 0 && clickedIdx < iGalleryEntries.Count())
        {
        SetSelectedFile(iGalleryEntries[clickedIdx].iFullPath, iGalleryEntries[clickedIdx].iSize);
        }
    }

void CNullhostBelleContainer::HandleBrowserTouch(const TPoint& aPoint)
    {
    if (aPoint.iY < 48)
        {
        TInt w = Rect().Width();
        TInt dWidth = w / 4;
        TInt idx = aPoint.iX / dWidth;
        if (idx == 0) { iCurrentBrowserPath.Copy(_L("C:\\")); SetView(ENullhostViewBrowser); }
        else if (idx == 1) { iCurrentBrowserPath.Copy(_L("E:\\")); SetView(ENullhostViewBrowser); }
        else if (idx == 2) { iCurrentBrowserPath.Copy(_L("F:\\")); SetView(ENullhostViewBrowser); }
        else SetView(ENullhostViewUpload);
        return;
        }

    TInt rowHeight = 44;
    TInt clickedIdx = (aPoint.iY - 48 + iScrollOffset) / rowHeight;
    if (clickedIdx >= 0 && clickedIdx < iBrowserEntries.Count())
        {
        const TNullhostFileEntry& e = iBrowserEntries[clickedIdx];
        if (e.iIsDir)
            {
            iCurrentBrowserPath.Copy(e.iFullPath);
            SetView(ENullhostViewBrowser);
            }
        else
            {
            SetSelectedFile(e.iFullPath, e.iSize);
            }
        }
    }

void CNullhostBelleContainer::HandleResultTouch(const TPoint& aPoint)
    {
    if (aPoint.iY >= 295 && aPoint.iY <= 350)
        {
        iSelectedFile.Zero();
        iSelectedFileSize = 0;
        SetView(ENullhostViewUpload);
        }
    }

// ----------------------------------------------------------------------------
// MNullhostHttpObserver
// ----------------------------------------------------------------------------
void CNullhostBelleContainer::OnUploadProgress(TInt /*aBytesSent*/, TInt /*aTotalBytes*/)
    {
    DrawNow();
    }

void CNullhostBelleContainer::OnUploadSuccess(const TDesC& aUrl, const TDesC& aRawUrl, const TDesC& /*aDownloadUrl*/)
    {
    iResultUrl.Copy(aUrl);
    iResultRawUrl.Copy(aRawUrl);
    iCurrentView = ENullhostViewResult;
    DrawNow();
    }

void CNullhostBelleContainer::OnUploadError(TInt /*aErrorCode*/, const TDesC& aErrorMessage)
    {
    ShowTextScreen(_L("Upload Failed"), aErrorMessage);
    }

// ----------------------------------------------------------------------------
// DRAWING ENGINE
// ----------------------------------------------------------------------------
void CNullhostBelleContainer::Draw(const TRect& aRect) const
    {
    if (!iBufferGc) return;

    // Background OLED dark charcoal
    iBufferGc->SetBrushColor(TRgb(18, 22, 28));
    iBufferGc->Clear(Rect());

    switch (iCurrentView)
        {
        case ENullhostViewUpload:
            DrawUploadView(*iBufferGc);
            break;
        case ENullhostViewGallery:
            DrawGalleryView(*iBufferGc);
            break;
        case ENullhostViewBrowser:
            DrawBrowserView(*iBufferGc);
            break;
        case ENullhostViewResult:
            DrawResultView(*iBufferGc);
            break;
        case ENullhostViewText:
            DrawTextView(*iBufferGc);
            break;
        default:
            break;
        }

    DrawStatusBar(*iBufferGc);
    DrawBottomToolbar(*iBufferGc);

    // Blit backbuffer to screen
    CWindowGc& gc = SystemGc();
    gc.BitBlt(aRect.iTl, iBufferBitmap, aRect);
    }

void CNullhostBelleContainer::DrawStatusBar(CBitmapContext& aGc) const
    {
    aGc.SetBrushColor(TRgb(12, 15, 20));
    aGc.SetPenColor(TRgb(32, 40, 52));
    aGc.DrawRect(TRect(0, 0, Rect().Width(), 36));

    const CFont* font = CEikonEnv::Static()->TitleFont();
    aGc.UseFont(font);
    aGc.SetPenColor(TRgb(0, 168, 232));
    aGc.DrawText(_L("Nullhost"), TPoint(14, 24));

    TTime now; now.HomeTime();
    TBuf<16> timeStr;
    now.FormatL(timeStr, _L("%H:%T"));
    aGc.SetPenColor(TRgb(160, 172, 188));
    aGc.DrawText(timeStr, TPoint(Rect().Width() - 56, 24));
    aGc.DiscardFont();
    }

void CNullhostBelleContainer::DrawBottomToolbar(CBitmapContext& aGc) const
    {
    TInt tbY = Rect().Height() - 56;
    TInt w = Rect().Width();
    aGc.SetBrushColor(TRgb(12, 15, 20));
    aGc.SetPenColor(TRgb(32, 40, 52));
    aGc.DrawRect(TRect(0, tbY, w, Rect().Height()));

    TInt btnWidth = w / 4;
    const CFont* font = CEikonEnv::Static()->NormalFont();
    aGc.UseFont(font);

    const TText* labels[4] = { _S("Options"), _S("Gallery"), _S("Browse"), _S("Upload") };
    for (TInt i = 0; i < 4; i++)
        {
        TInt bx = i * btnWidth;
        TBool active = (i == 1 && iCurrentView == ENullhostViewGallery) ||
                       (i == 2 && iCurrentView == ENullhostViewBrowser) ||
                       (i == 3 && iCurrentView == ENullhostViewUpload);
        if (active)
            {
            aGc.SetBrushColor(TRgb(28, 34, 44));
            aGc.SetPenColor(TRgb(0, 168, 232));
            aGc.DrawRoundRect(TRect(bx + 4, tbY + 6, bx + btnWidth - 4, Rect().Height() - 6), TSize(8, 8));
            }
        aGc.SetPenColor(active ? TRgb(245, 248, 250) : TRgb(140, 150, 165));
        TPtrC lbl(labels[i]);
        aGc.DrawText(lbl, TPoint(bx + 14, tbY + 34));
        }
    aGc.DiscardFont();
    }

void CNullhostBelleContainer::DrawUploadView(CBitmapContext& aGc) const
    {
    const CFont* titleFont = CEikonEnv::Static()->TitleFont();
    const CFont* normFont = CEikonEnv::Static()->NormalFont();

    aGc.UseFont(titleFont);
    aGc.SetPenColor(TRgb(245, 248, 250));
    aGc.DrawText(_L("Private Ephemeral Host"), TPoint(20, 70));
    aGc.DiscardFont();

    // File card
    aGc.SetBrushColor(TRgb(28, 34, 44));
    aGc.SetPenColor(TRgb(42, 50, 64));
    aGc.DrawRoundRect(TRect(16, 95, Rect().Width() - 16, 175), TSize(10, 10));

    aGc.UseFont(normFont);
    if (iSelectedFile.Length() > 0)
        {
        TParse parse; parse.Set(iSelectedFile, NULL, NULL);
        aGc.SetPenColor(TRgb(245, 248, 250));
        aGc.DrawText(parse.NameAndExt(), TPoint(28, 126));

        TBuf<32> szStr;
        szStr.Format(_L("%.1f KB | Ready to upload"), (TReal)iSelectedFileSize / 1024.0);
        aGc.SetPenColor(TRgb(46, 204, 113));
        aGc.DrawText(szStr, TPoint(28, 152));
        }
    else
        {
        aGc.SetPenColor(TRgb(140, 150, 165));
        aGc.DrawText(_L("No file selected"), TPoint(28, 126));
        aGc.DrawText(_L("Tap 'Browse' or 'Gallery' to pick"), TPoint(28, 152));
        }

    // Option pills
    TInt pW = (Rect().Width() - 44) / 2;
    aGc.SetBrushColor(TRgb(36, 44, 58));
    aGc.DrawRoundRect(TRect(16, 190, 16 + pW, 230), TSize(8, 8));
    TBuf<32> expTxt; expTxt.Format(_L("Expiry: %S"), &iExpiry);
    aGc.SetPenColor(TRgb(245, 248, 250));
    aGc.DrawText(expTxt, TPoint(26, 216));

    aGc.DrawRoundRect(TRect(28 + pW, 190, Rect().Width() - 16, 230), TSize(8, 8));
    TBuf<32> burnTxt;
    if (iBurnViews.Compare(_L("0")) == 0) burnTxt.Copy(_L("Burn: Off"));
    else burnTxt.Format(_L("Burn: %S Views"), &iBurnViews);
    aGc.DrawText(burnTxt, TPoint(38 + pW, 216));

    // Upload Button (Accent pill)
    aGc.SetBrushColor(TRgb(0, 168, 232));
    aGc.SetPenColor(TRgb(0, 200, 255));
    aGc.DrawRoundRect(TRect(16, 295, Rect().Width() - 16, 350), TSize(12, 12));

    aGc.UseFont(titleFont);
    aGc.SetPenColor(TRgb(10, 15, 20));
    if (iSelectedFile.Length() > 0)
        aGc.DrawText(_L("UPLOAD NOW"), TPoint(80, 330));
    else
        aGc.DrawText(_L("CHOOSE A FILE"), TPoint(75, 330));
    aGc.DiscardFont();

    aGc.DiscardFont();
    }

void CNullhostBelleContainer::DrawGalleryView(CBitmapContext& aGc) const
    {
    const CFont* font = CEikonEnv::Static()->NormalFont();
    aGc.UseFont(font);

    TInt y = 56 - iScrollOffset;
    for (TInt i = 0; i < iGalleryEntries.Count(); i++)
        {
        if (y >= 40 && y < Rect().Height() - 60)
            {
            aGc.SetBrushColor(TRgb(28, 34, 44));
            aGc.SetPenColor(TRgb(42, 50, 64));
            aGc.DrawRoundRect(TRect(12, y, Rect().Width() - 12, y + 50), TSize(8, 8));

            aGc.SetPenColor(TRgb(245, 248, 250));
            aGc.DrawText(iGalleryEntries[i].iName, TPoint(24, y + 24));

            TBuf<32> szStr;
            szStr.Format(_L("%.1f KB"), (TReal)iGalleryEntries[i].iSize / 1024.0);
            aGc.SetPenColor(TRgb(140, 150, 165));
            aGc.DrawText(szStr, TPoint(24, y + 42));
            }
        y += 56;
        }
    aGc.DiscardFont();
    }

void CNullhostBelleContainer::DrawBrowserView(CBitmapContext& aGc) const
    {
    const CFont* font = CEikonEnv::Static()->NormalFont();
    aGc.UseFont(font);

    TInt y = 56 - iScrollOffset;
    for (TInt i = 0; i < iBrowserEntries.Count(); i++)
        {
        if (y >= 40 && y < Rect().Height() - 60)
            {
            aGc.SetBrushColor(TRgb(28, 34, 44));
            aGc.SetPenColor(TRgb(42, 50, 64));
            aGc.DrawRoundRect(TRect(12, y, Rect().Width() - 12, y + 40), TSize(6, 6));

            TBuf<64> dispName;
            if (iBrowserEntries[i].iIsDir) dispName.Format(_L("[DIR] %S"), &iBrowserEntries[i].iName);
            else dispName.Format(_L("[FILE] %S"), &iBrowserEntries[i].iName);

            aGc.SetPenColor(iBrowserEntries[i].iIsDir ? TRgb(0, 168, 232) : TRgb(245, 248, 250));
            aGc.DrawText(dispName, TPoint(22, y + 26));
            }
        y += 44;
        }
    aGc.DiscardFont();
    }

void CNullhostBelleContainer::DrawResultView(CBitmapContext& aGc) const
    {
    const CFont* titleFont = CEikonEnv::Static()->TitleFont();
    const CFont* normFont = CEikonEnv::Static()->NormalFont();

    aGc.UseFont(titleFont);
    aGc.SetPenColor(TRgb(46, 204, 113));
    aGc.DrawText(_L("Upload Succeeded!"), TPoint(20, 70));

    aGc.SetBrushColor(TRgb(28, 34, 44));
    aGc.SetPenColor(TRgb(42, 50, 64));
    aGc.DrawRoundRect(TRect(16, 95, Rect().Width() - 16, 200), TSize(10, 10));

    aGc.UseFont(normFont);
    aGc.SetPenColor(TRgb(140, 150, 165));
    aGc.DrawText(_L("Short Link:"), TPoint(28, 125));
    aGc.SetPenColor(TRgb(245, 248, 250));
    aGc.DrawText(iResultUrl, TPoint(28, 145));

    aGc.SetPenColor(TRgb(140, 150, 165));
    aGc.DrawText(_L("Raw Link:"), TPoint(28, 170));
    aGc.SetPenColor(TRgb(245, 248, 250));
    aGc.DrawText(iResultRawUrl, TPoint(28, 190));

    // Upload another button
    aGc.SetBrushColor(TRgb(0, 168, 232));
    aGc.SetPenColor(TRgb(0, 200, 255));
    aGc.DrawRoundRect(TRect(16, 295, Rect().Width() - 16, 350), TSize(12, 12));

    aGc.UseFont(titleFont);
    aGc.SetPenColor(TRgb(10, 15, 20));
    aGc.DrawText(_L("UPLOAD ANOTHER"), TPoint(65, 330));

    aGc.DiscardFont();
    }

void CNullhostBelleContainer::DrawTextView(CBitmapContext& aGc) const
    {
    const CFont* titleFont = CEikonEnv::Static()->TitleFont();
    const CFont* normFont = CEikonEnv::Static()->NormalFont();

    aGc.UseFont(titleFont);
    aGc.SetPenColor(TRgb(0, 168, 232));
    aGc.DrawText(iTextTitle, TPoint(16, 68));
    aGc.DiscardFont();

    if (iTextContent)
        {
        aGc.UseFont(normFont);
        aGc.SetPenColor(TRgb(245, 248, 250));
        aGc.DrawText(*iTextContent, TPoint(16, 100));
        aGc.DiscardFont();
        }
    }
