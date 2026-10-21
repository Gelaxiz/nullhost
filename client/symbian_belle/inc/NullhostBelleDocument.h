#ifndef NULLHOSTBELLEDOCUMENT_H
#define NULLHOSTBELLEDOCUMENT_H

#include <akndoc.h>

class CEikAppUi;

class CNullhostBelleDocument : public CAknDocument
    {
public:
    static CNullhostBelleDocument* NewL(CEikApplication& aApp);
    CEikAppUi* CreateAppUiL();

private:
    CNullhostBelleDocument(CEikApplication& aApp);
    };

#endif // NULLHOSTBELLEDOCUMENT_H
