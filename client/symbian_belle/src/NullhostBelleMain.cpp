#include "NullhostBelleApplication.h"
#include <eikstart.h>

GLDEF_C TInt E32Main()
    {
    return EikStart::RunApplication(NewApplication);
    }

LOCAL_C CApaApplication* NewApplication()
    {
    return new CNullhostBelleApplication;
    }
