#ifndef NULLHOSTS60CONTAINER_H
#define NULLHOSTS60CONTAINER_H

#include <coecntrl.h>
#include <fbs.h>
#include <bitdev.h>
#include "NullhostS60.hrh"
#include "NullhostFileBrowser.h"
#include "NullhostGalleryScanner.h"
#include "NullhostHttpEngine.h"

class CNullhostS60Container : public CCoeControl, public MNullhostHttpObserver
    {
public:
    static CNullhostS60Container* NewL(const TRect& aRect);
    ~CNullhostS60Container();

    void ConstructL(const TRect& aRect);

    // Tab & View control
    void SetTab(TNullhostS60Tab aTab);
    TNullhostS60Tab CurrentTab() const { return iCurrentTab; }

    // Actions
    void SetSelectedFile(const TDesC& aPath, TInt64 aSize);
    void StartUpload();
    void SetAccount(const TDesC& aAccountId);
    void ShowViewer(const TDesC& aTitle, const TDesC& aContent);

    // CCoeControl overrides
    void Draw(const TRect& aRect) const;
    void SizeChanged();
    TKeyResponse OfferKeyEventL(const TKeyEvent& aKeyEvent, TEventCode aType);

    // MNullhostHttpObserver
    void OnUploadProgress(TInt aBytesSent, TInt aTotalBytes);
    void OnUploadSuccess(const TDesC& aUrl, const TDesC& aRawUrl, const TDesC& aDownloadUrl);
    void OnUploadError(TInt aErrorCode, const TDesC& aErrorMessage);

private:
    CNullhostS60Container();

    void InitDoubleBufferL();
    void DrawHeader(CBitmapContext& aGc) const;
    void DrawTabs(CBitmapContext& aGc) const;
    void DrawTabUpload(CBitmapContext& aGc) const;
    void DrawTabBrowse(CBitmapContext& aGc) const;
    void DrawTabGallery(CBitmapContext& aGc) const;
    void DrawTabAccount(CBitmapContext& aGc) const;
    void DrawViewer(CBitmapContext& aGc) const;

    void HandleUploadSelectL();
    void HandleBrowseSelectL();
    void HandleGallerySelectL();

private:
    TNullhostS60Tab iCurrentTab;

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

    // Navigation cursors
    TInt iCursorUpload;
    TInt iCursorBrowse;
    TInt iCursorGallery;
    TInt iViewerScroll;

    // Browser & Gallery data
    RArray<TNullhostFileEntry> iBrowserEntries;
    RArray<TNullhostFileEntry> iGalleryEntries;
    TFileName iCurrentBrowserPath;

    // Result
    TBuf<128> iResultUrl;

    // Viewer
    TBuf<64> iViewerTitle;
    HBufC* iViewerContent;
    };

#endif // NULLHOSTS60CONTAINER_H
