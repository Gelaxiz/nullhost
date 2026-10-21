#include "NullhostBelleApplication.h"
#include "NullhostBelleDocument.h"

TUid CNullhostBelleApplication::AppDllUid() const
    {
    return KUidNullhostBelleApp;
    }

CApaDocument* CNullhostBelleApplication::CreateDocumentL()
    {
    return CNullhostBelleDocument::NewL(*this);
    }
