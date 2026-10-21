#include "NullhostBelleAppUi.h"
#include <avkon.hrh>
#include <eikenv.h>

void CNullhostBelleAppUi::ConstructL()
    {
    BaseConstructL(EAknEnableSkin);
    iContainer = CNullhostBelleContainer::NewL(ClientRect());
    AddToStackL(iContainer);
    }

CNullhostBelleAppUi::~CNullhostBelleAppUi()
    {
    if (iContainer)
        {
        RemoveFromStack(iContainer);
        delete iContainer;
        }
    }

void CNullhostBelleAppUi::HandleCommandL(TInt aCommand)
    {
    switch (aCommand)
        {
        case EEikCmdExit:
        case EAknSoftkeyExit:
        case EAknSoftkeyBack:
            Exit();
            break;
        case ENullhostCmdUpload:
            iContainer->StartUpload();
            break;
        case ENullhostCmdBrowse:
            iContainer->SetView(ENullhostViewBrowser);
            break;
        case ENullhostCmdGallery:
            iContainer->SetView(ENullhostViewGallery);
            break;
        case ENullhostCmdAccount:
            ShowAccountDialogL();
            break;
        case ENullhostCmdTermsOfService:
            ShowTosDialogL();
            break;
        case ENullhostCmdPrivacyPolicy:
            ShowPrivacyDialogL();
            break;
        case ENullhostCmdAbout:
            ShowAboutDialogL();
            break;
        default:
            break;
        }
    }

void CNullhostBelleAppUi::ShowTosDialogL()
    {
    _LIT(KTosTitle, "Terms of Service");
    _LIT(KTosContent, 
        "NULLHOST TERMS OF SERVICE\n\n"
        "1. ACCEPTABLE USE:\n"
        "Nullhost provides privacy-focused ephemeral file and paste sharing.\n"
        "Prohibited content: malware, exploits, copyright-infringing content, CSAM, and illegal materials.\n\n"
        "2. RETENTION:\n"
        "Expired files and view-limited files are purged permanently.\n\n"
        "3. DISCLAIMER:\n"
        "Service is provided AS-IS without warranty."
    );
    iContainer->ShowTextScreen(KTosTitle, KTosContent);
    }

void CNullhostBelleAppUi::ShowPrivacyDialogL()
    {
    _LIT(KPrivTitle, "Privacy Policy");
    _LIT(KPrivContent,
        "NULLHOST PRIVACY POLICY\n\n"
        "1. ZERO TRACKING:\n"
        "Nullhost does NOT collect user tracking cookies, IP telemetry, or hardware identifiers.\n\n"
        "2. EPHEMERAL STORAGE:\n"
        "When files expire or burn out, all data and metadata are erased immediately.\n\n"
        "3. AUTHENTIC APP SECURITY:\n"
        "Native clients use local cryptographic HMAC signatures for secure verification."
    );
    iContainer->ShowTextScreen(KPrivTitle, KPrivContent);
    }

void CNullhostBelleAppUi::ShowAboutDialogL()
    {
    _LIT(KAboutTitle, "About Nullhost");
    _LIT(KAboutContent,
        "NULLHOST FOR SYMBIAN\n\n"
        "Version: 1.0.0 (Native Symbian C++)\n"
        "Target Platform: Symbian ^3 / Belle FP2\n"
        "Dependencies: NONE (100% Native C++)\n"
        "Qt / WRT: ZERO\n\n"
        "Engineered by Gelaxiz Labs (c) 2026\n"
        "Open source under MIT License."
    );
    iContainer->ShowTextScreen(KAboutTitle, KAboutContent);
    }

void CNullhostBelleAppUi::ShowAccountDialogL()
    {
    _LIT(KAccTitle, "Account Settings");
    _LIT(KAccContent,
        "ACCOUNT MANAGEMENT\n\n"
        "To link your uploads, enter your 24-digit Account ID.\n"
        "Your account tracks your active uploads and quota.\n"
        "Configured via app settings."
    );
    iContainer->ShowTextScreen(KAccTitle, KAccContent);
    }
