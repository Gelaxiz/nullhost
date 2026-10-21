#ifndef NULLHOSTBELLECONTAINER_H
#define NULLHOSTBELLECONTAINER_H

#include <coecntrl.h>
#include <fbs.h>
#include <bitdev.h>
#include "NullhostBelle.hrh"
#include "NullhostFileBrowser.h"
#include "NullhostGalleryScanner.h"
#include "NullhostHttpEngine.h"

class CNullhostBelleContainer : public CCoeControl, public MNullhostHttpObserver
    {
public:
    static CNullhostBelleContainer* NewL(const TRect& aRect);
    ~CNullhostBelleContainer();

    void ConstructL(const TRect& aRect);

    // View Switching
    void SetView(TNullhostViewType aView);
    TNullhostViewType CurrentView() const { return iCurrentView; }

    // Actions
    void SetSelectedFile(const TDesC& aPath, TInt64 aSize);
    void StartUpload();
    void SetAccount(const TDesC& aAccountId);
    void ShowTextScreen(const TDesC& aTitle, const TDesC& aContent);

    // CCoeControl overrides
    void Draw(const TRect& aRect) const;
    void SizeChanged();
    void HandlePointerEventL(const TPointerEvent& aPointerEvent);

    // MNullhostHttpObserver
    void OnUploadProgress(TInt aBytesSent, TInt aTotalBytes);
    void OnUploadSuccess(const TDesC& aUrl, const TDesC& aRawUrl, const TDesC& aDownloadUrl);
    void OnUploadError(TInt aErrorCode, const TDesC& aErrorMessage);

private:
    CNullhostBelleContainer();

    void InitDoubleBufferL();
    void DrawStatusBar(CBitmapContext& aGc) const;
    void DrawBottomToolbar(CBitmapContext& aGc) const;
    void DrawUploadView(CBitmapContext& aGc) const;
    void DrawGalleryView(CBitmapContext& aGc) const;
    void DrawBrowserView(CBitmapContext& aGc) const;
    void DrawResultView(CBitmapContext& aGc) const;
    void DrawTextView(CBitmapContext& aGc) const;

    void HandleToolbarTouch(const TPoint& aPoint);
    void HandleUploadTouch(const TPoint& aPoint);
    void HandleGalleryTouch(const TPoint& aPoint);
    void HandleBrowserTouch(const TPoint& aPoint);
    void HandleResultTouch(const TPoint& aPoint);

private:
    TNullhostViewType iCurrentView;

    // Double buffer
    CFbsBitmap* iBufferBitmap;
    CFbsBitmapDevice* iBufferDevice;
    CFbsBitGc* iBufferGc;

    // Controllers
    CNullhostFileBrowser* iFileBrowser;
    CNullhostGalleryScanner* iGalleryScanner;
    CNullhostHttpEngine* iHttpEngine;

    // State
    TFileName iSelectedFile;
    TInt64 iSelectedFileSize;
    TFileName iServerUrl;
    TBuf<16> iExpiry;
    TBuf<16> iBurnViews;
    TBuf<64> iPassword;
    TBuf<64> iCustomSlug;
    TBuf<32> iAccountId;

    // Browser & Gallery data
    RArray<TNullhostFileEntry> iBrowserEntries;
    RArray<TNullhostFileEntry> iGalleryEntries;
    TFileName iCurrentBrowserPath;
    TInt iScrollOffset;

    // Result URLs
    TBuf<128> iResultUrl;
    TBuf<128> iResultRawUrl;

    // Text view data
    TBuf<64> iTextTitle;
    HBufC* iTextContent;
    };

#endif // NULLHOSTBELLECONTAINER_H
