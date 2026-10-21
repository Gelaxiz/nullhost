#ifndef NULLHOSTS60APPUI_H
#define NULLHOSTS60APPUI_H

#include <aknviewappui.h>
#include "NullhostS60Container.h"

class CNullhostS60AppUi : public CAknViewAppUi
    {
public:
    void ConstructL();
    ~CNullhostS60AppUi();

    void HandleCommandL(TInt aCommand);

private:
    void ShowTosL();
    void ShowPrivacyL();
    void ShowAboutL();

private:
    CNullhostS60Container* iContainer;
    };

#endif // NULLHOSTS60APPUI_H
