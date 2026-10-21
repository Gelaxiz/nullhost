#include "NullhostS60Document.h"
#include "NullhostS60AppUi.h"

CNullhostS60Document* CNullhostS60Document::NewL(CEikApplication& aApp)
    {
    return new (ELeave) CNullhostS60Document(aApp);
    }

CNullhostS60Document::CNullhostS60Document(CEikApplication& aApp)
    : CAknDocument(aApp)
    {
    }

CEikAppUi* CNullhostS60Document::CreateAppUiL()
    {
    return new (ELeave) CNullhostS60AppUi();
    }
