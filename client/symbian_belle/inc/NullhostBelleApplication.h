#ifndef NULLHOSTBELLEAPPLICATION_H
#define NULLHOSTBELLEAPPLICATION_H

#include <aknapp.h>

const TUid KUidNullhostBelleApp = { 0xE009F810 };

class CNullhostBelleApplication : public CAknApplication
    {
public:
    TUid AppDllUid() const;

protected:
    CApaDocument* CreateDocumentL();
    };

#endif // NULLHOSTBELLEAPPLICATION_H
