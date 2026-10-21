#include "NullhostS60Application.h"
#include "NullhostS60Document.h"

TUid CNullhostS60Application::AppDllUid() const
    {
    return KUidNullhostS60App;
    }

CApaDocument* CNullhostS60Application::CreateDocumentL()
    {
    return CNullhostS60Document::NewL(*this);
    }
