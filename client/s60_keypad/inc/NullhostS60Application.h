#ifndef NULLHOSTS60APPLICATION_H
#define NULLHOSTS60APPLICATION_H

#include <aknapp.h>

const TUid KUidNullhostS60App = { 0xE009F811 };

class CNullhostS60Application : public CAknApplication
    {
public:
    TUid AppDllUid() const;

protected:
    CApaDocument* CreateDocumentL();
    };

#endif // NULLHOSTS60APPLICATION_H
