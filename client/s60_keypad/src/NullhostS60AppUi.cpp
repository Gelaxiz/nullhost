#include "NullhostS60AppUi.h"
#include <avkon.hrh>

void CNullhostS60AppUi::ConstructL()
    {
    BaseConstructL(EAknEnableSkin);
    iContainer = CNullhostS60Container::NewL(ClientRect());
    AddToStackL(iContainer);
    }

CNullhostS60AppUi::~CNullhostS60AppUi()
    {
    if (iContainer)
        {
        RemoveFromStack(iContainer);
        delete iContainer;
        }
    }

void CNullhostS60AppUi::HandleCommandL(TInt aCommand)
    {
    switch (aCommand)
        {
        case EEikCmdExit:
        case EAknSoftkeyExit:
        case EAknSoftkeyBack:
            Exit();
            break;
        case ENullhostS60CmdUpload:
            iContainer->StartUpload();
            break;
        case ENullhostS60CmdSystemPick:
        case ENullhostS60CmdBrowse:
            iContainer->SetTab(ETabBrowse);
            break;
        case ENullhostS60CmdGallery:
            iContainer->SetTab(ETabGallery);
            break;
        case ENullhostS60CmdAccount:
            iContainer->SetTab(ETabAccount);
            break;
        case ENullhostS60CmdTermsOfService:
            ShowTosL();
            break;
        case ENullhostS60CmdPrivacyPolicy:
            ShowPrivacyL();
            break;
        case ENullhostS60CmdAbout:
            ShowAboutL();
            break;
        default:
            break;
        }
    }

void CNullhostS60AppUi::ShowTosL()
    {
    _LIT(KTosTitle, "Terms of Service");
    _LIT(KTosContent,
        "NULLHOST S60 TERMS\n\n"
        "1. ACCEPTABLE USE:\n"
        "Ephemeral & permanent file hosting.\n"
        "Zero tolerance for malware, exploits, CSAM, copyright infringement.\n\n"
        "2. RETENTION:\n"
        "Expired files purged permanently.\n\n"
        "3. DISCLAIMER:\n"
        "Provided AS-IS without warranty."
    );
    iContainer->ShowViewer(KTosTitle, KTosContent);
    }

void CNullhostS60AppUi::ShowPrivacyL()
    {
    _LIT(KPrivTitle, "Privacy Policy");
    _LIT(KPrivContent,
        "NULLHOST S60 PRIVACY\n\n"
        "1. ZERO TRACKING:\n"
        "No telemetry, no tracking cookies, no device logging.\n\n"
        "2. EPHEMERAL STORAGE:\n"
        "Expired and burned files erased immediately.\n\n"
        "3. AUTHENTIC APP SECURITY:\n"
        "Local cryptographic HMAC signatures."
    );
    iContainer->ShowViewer(KPrivTitle, KPrivContent);
    }

void CNullhostS60AppUi::ShowAboutL()
    {
    _LIT(KAboutTitle, "About Nullhost");
    _LIT(KAboutContent,
        "NULLHOST S60 KEYPAD\n\n"
        "Version: 1.0.0 (Native Symbian C++)\n"
        "Target: S60 3rd / 5th Edition Keypad\n"
        "Zero Qt / Zero Web Runtime (WRT)\n\n"
        "Crafted by Gelaxiz Labs (c) 2026\n"
        "Open source under MIT License."
    );
    iContainer->ShowViewer(KAboutTitle, KAboutContent);
    }
