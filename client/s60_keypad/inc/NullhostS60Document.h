#ifndef NULLHOSTS60DOCUMENT_H
#define NULLHOSTS60DOCUMENT_H

#include <akndoc.h>

class CEikAppUi;

class CNullhostS60Document : public CAknDocument
    {
public:
    static CNullhostS60Document* NewL(CEikApplication& aApp);
    CEikAppUi* CreateAppUiL();

private:
    CNullhostS60Document(CEikApplication& aApp);
    };

#endif // NULLHOSTS60DOCUMENT_H
