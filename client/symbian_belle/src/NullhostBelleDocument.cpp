#include "NullhostBelleDocument.h"
#include "NullhostBelleAppUi.h"

CNullhostBelleDocument* CNullhostBelleDocument::NewL(CEikApplication& aApp)
    {
    return new (ELeave) CNullhostBelleDocument(aApp);
    }

CNullhostBelleDocument::CNullhostBelleDocument(CEikApplication& aApp)
    : CAknDocument(aApp)
    {
    }

CEikAppUi* CNullhostBelleDocument::CreateAppUiL()
    {
    return new (ELeave) CNullhostBelleAppUi();
    }
