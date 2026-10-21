#ifndef NULLHOSTGALLERYSCANNER_H
#define NULLHOSTGALLERYSCANNER_H

#include <e32base.h>
#include <f32file.h>
#include "NullhostFileBrowser.h"

class CNullhostGalleryScanner : public CBase
    {
public:
    static CNullhostGalleryScanner* NewL();
    ~CNullhostGalleryScanner();

    void ScanImagesL(RArray<TNullhostFileEntry>& aImages);

private:
    CNullhostGalleryScanner();
    void ConstructL();
    void ScanDirectoryRecursiveL(const TDesC& aPath, TInt aDepth, RArray<TNullhostFileEntry>& aImages);

private:
    RFs iFs;
    };

#endif // NULLHOSTGALLERYSCANNER_H
