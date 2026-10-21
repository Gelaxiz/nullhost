#ifndef NULLHOSTBELLEAPPUI_H
#define NULLHOSTBELLEAPPUI_H

#include <aknviewappui.h>
#include "NullhostBelleContainer.h"

class CNullhostBelleAppUi : public CAknViewAppUi
    {
public:
    void ConstructL();
    ~CNullhostBelleAppUi();

    void HandleCommandL(TInt aCommand);

private:
    void ShowTosDialogL();
    void ShowPrivacyDialogL();
    void ShowAboutDialogL();
    void ShowAccountDialogL();

private:
    CNullhostBelleContainer* iContainer;
    };

#endif // NULLHOSTBELLEAPPUI_H
