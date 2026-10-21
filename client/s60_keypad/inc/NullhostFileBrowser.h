#ifndef NULLHOSTFILEBROWSER_H
#define NULLHOSTFILEBROWSER_H

#include <e32base.h>
#include <f32file.h>

struct TNullhostFileEntry
    {
    TFileName iName;
    TFileName iFullPath;
    TBool iIsDir;
    TInt64 iSize;
    };

class CNullhostFileBrowser : public CBase
    {
public:
    static CNullhostFileBrowser* NewL();
    ~CNullhostFileBrowser();

    void ScanDrivesL(RArray<TNullhostFileEntry>& aEntries);
    void ReadDirectoryL(const TDesC& aPath, RArray<TNullhostFileEntry>& aEntries);

private:
    CNullhostFileBrowser();
    void ConstructL();

private:
    RFs iFs;
    };

#endif // NULLHOSTFILEBROWSER_H
