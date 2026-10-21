#include "NullhostGalleryScanner.h"

CNullhostGalleryScanner* CNullhostGalleryScanner::NewL()
    {
    CNullhostGalleryScanner* self = new (ELeave) CNullhostGalleryScanner();
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
    }

CNullhostGalleryScanner::CNullhostGalleryScanner()
    {
    }

void CNullhostGalleryScanner::ConstructL()
    {
    User::LeaveIfError(iFs.Connect());
    }

CNullhostGalleryScanner::~CNullhostGalleryScanner()
    {
    iFs.Close();
    }

void CNullhostGalleryScanner::ScanImagesL(RArray<TNullhostFileEntry>& aImages)
    {
    aImages.Reset();

    const TText* const KScanRoots[] = 
        {
        _S("E:\\Images\\"),
        _S("C:\\Data\\Images\\"),
        _S("F:\\Images\\"),
        _S("E:\\DCIM\\"),
        _S("C:\\Data\\Others\\")
        };

    for (TInt i = 0; i < 5; i++)
        {
        TPtrC rootPath(KScanRoots[i]);
        TUint att;
        if (iFs.Att(rootPath, att) == KErrNone)
            {
            ScanDirectoryRecursiveL(rootPath, 0, aImages);
            }
        }
    }

void CNullhostGalleryScanner::ScanDirectoryRecursiveL(const TDesC& aPath, TInt aDepth, RArray<TNullhostFileEntry>& aImages)
    {
    if (aDepth > 3 || aImages.Count() >= 100) return;

    CDir* dirList = NULL;
    CDir* fileList = NULL;

    TInt err = iFs.GetDir(aPath, KEntryAttMaskSupported, ESortByName, dirList, fileList);
    if (err != KErrNone) return;

    if (fileList)
        {
        for (TInt i = 0; i < fileList->Count(); i++)
            {
            const TEntry& e = (*fileList)[i];
            TParse parse;
            parse.Set(e.iName, NULL, NULL);
            TPtrC ext = parse.Ext();

            if (ext.CompareF(_L(".jpg")) == 0 ||
                ext.CompareF(_L(".jpeg")) == 0 ||
                ext.CompareF(_L(".png")) == 0 ||
                ext.CompareF(_L(".gif")) == 0)
                {
                TNullhostFileEntry entry;
                entry.iName.Copy(e.iName);
                entry.iFullPath.Copy(aPath);
                entry.iFullPath.Append(e.iName);
                entry.iIsDir = EFalse;
                entry.iSize = e.iFileSize;
                aImages.AppendL(entry);
                }
            }
        delete fileList;
        }

    if (dirList)
        {
        for (TInt i = 0; i < dirList->Count(); i++)
            {
            const TEntry& e = (*dirList)[i];
            TFileName subDir(aPath);
            subDir.Append(e.iName);
            subDir.Append(_L("\\"));
            ScanDirectoryRecursiveL(subDir, aDepth + 1, aImages);
            }
        delete dirList;
        }
    }
