#include "NullhostFileBrowser.h"

CNullhostFileBrowser* CNullhostFileBrowser::NewL()
    {
    CNullhostFileBrowser* self = new (ELeave) CNullhostFileBrowser();
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
    }

CNullhostFileBrowser::CNullhostFileBrowser()
    {
    }

void CNullhostFileBrowser::ConstructL()
    {
    User::LeaveIfError(iFs.Connect());
    }

CNullhostFileBrowser::~CNullhostFileBrowser()
    {
    iFs.Close();
    }

void CNullhostFileBrowser::ScanDrivesL(RArray<TNullhostFileEntry>& aEntries)
    {
    aEntries.Reset();
    TDriveList driveList;
    User::LeaveIfError(iFs.DriveList(driveList));

    for (TInt driveNum = EDriveA; driveNum <= EDriveZ; driveNum++)
        {
        if (driveList[driveNum])
            {
            TDriveInfo info;
            if (iFs.Drive(info, driveNum) == KErrNone)
                {
                TNullhostFileEntry entry;
                TChar driveLetter;
                iFs.DriveToChar(driveNum, driveLetter);
                entry.iFullPath.Format(_L("%c:\\"), (TUint)driveLetter);
                
                if (driveNum == EDriveC)
                    entry.iName.Copy(_L("C: Phone Memory"));
                else if (driveNum == EDriveE)
                    entry.iName.Copy(_L("E: Mass Storage"));
                else if (driveNum == EDriveF)
                    entry.iName.Copy(_L("F: Memory Card"));
                else
                    entry.iName.Format(_L("%c: Drive"), (TUint)driveLetter);

                entry.iIsDir = ETrue;
                entry.iSize = info.iTotalSpaceInBytes;
                aEntries.AppendL(entry);
                }
            }
        }
    }

void CNullhostFileBrowser::ReadDirectoryL(const TDesC& aPath, RArray<TNullhostFileEntry>& aEntries)
    {
    aEntries.Reset();

    // Add parent link if not root
    if (aPath.Length() > 3)
        {
        TNullhostFileEntry parentEntry;
        parentEntry.iName.Copy(_L(".. (Parent Folder)"));
        TParse parse;
        parse.Set(aPath, NULL, NULL);
        parentEntry.iFullPath.Copy(parse.DriveAndPath());
        parentEntry.iIsDir = ETrue;
        parentEntry.iSize = 0;
        aEntries.AppendL(parentEntry);
        }

    CDir* dirList = NULL;
    CDir* fileList = NULL;

    TInt err = iFs.GetDir(aPath, KEntryAttMaskSupported, ESortByName, dirList, fileList);
    if (err == KErrNone)
        {
        if (dirList)
            {
            for (TInt i = 0; i < dirList->Count(); i++)
                {
                const TEntry& e = (*dirList)[i];
                TNullhostFileEntry entry;
                entry.iName.Copy(e.iName);
                entry.iFullPath.Copy(aPath);
                if (entry.iFullPath[entry.iFullPath.Length()-1] != '\\')
                    entry.iFullPath.Append(_L("\\"));
                entry.iFullPath.Append(e.iName);
                entry.iFullPath.Append(_L("\\"));
                entry.iIsDir = ETrue;
                entry.iSize = 0;
                aEntries.AppendL(entry);
                }
            delete dirList;
            }

        if (fileList)
            {
            for (TInt i = 0; i < fileList->Count(); i++)
                {
                const TEntry& e = (*fileList)[i];
                TNullhostFileEntry entry;
                entry.iName.Copy(e.iName);
                entry.iFullPath.Copy(aPath);
                if (entry.iFullPath[entry.iFullPath.Length()-1] != '\\')
                    entry.iFullPath.Append(_L("\\"));
                entry.iFullPath.Append(e.iName);
                entry.iIsDir = EFalse;
                entry.iSize = e.iFileSize;
                aEntries.AppendL(entry);
                }
            delete fileList;
            }
        }
    }
