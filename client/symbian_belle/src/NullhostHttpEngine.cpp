#include "NullhostHttpEngine.h"
#include "NullhostCrypto.h"

CNullhostHttpEngine* CNullhostHttpEngine::NewL(MNullhostHttpObserver& aObserver)
    {
    CNullhostHttpEngine* self = new (ELeave) CNullhostHttpEngine(aObserver);
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
    }

CNullhostHttpEngine::CNullhostHttpEngine(MNullhostHttpObserver& aObserver)
    : CActive(EPriorityStandard),
      iObserver(aObserver),
      iState(EStateIdle),
      iTimeOffset(0),
      iResponseBody(NULL)
    {
    CActiveScheduler::Add(this);
    }

void CNullhostHttpEngine::ConstructL()
    {
    User::LeaveIfError(iFs.Connect());
    }

CNullhostHttpEngine::~CNullhostHttpEngine()
    {
    Cancel();
    delete iResponseBody;
    iFs.Close();
    }

void CNullhostHttpEngine::AuthenticateAndUploadL(
    const TDesC& aServerUrl,
    const TDesC& aFilePath,
    const TDesC& aExpiry,
    const TDesC& aBurnViews,
    const TDesC& aPassword,
    const TDesC& aCustomSlug,
    const TDesC& aAccountId)
    {
    if (IsActive()) Cancel();

    iServerUrl.Copy(aServerUrl);
    iFilePath.Copy(aFilePath);
    iExpiry.Copy(aExpiry);
    iBurnViews.Copy(aBurnViews);
    iPassword.Copy(aPassword);
    iCustomSlug.Copy(aCustomSlug);
    iAccountId.Copy(aAccountId);

    StartAuthRequestL();
    }

void CNullhostHttpEngine::StartAuthRequestL()
    {
    iState = EStateAuthenticating;

    // Construct HMAC handshake
    TTime now;
    now.HomeTime();
    TInt64 seconds = now.Int64() / 1000000 + iTimeOffset;

    _LIT8(KClientId, "symbian-belle-fp2");
    _LIT8(KMobileSecret, "nullhost_secret_mobile_app_key_2026");

    TBuf8<16> nonce;
    TInt64 randSeed = now.Int64();
    for (TInt i = 0; i < 16; i++)
        {
        TInt r = Math::Rand(randSeed) % 16;
        _LIT8(KHex, "0123456789abcdef");
        nonce.Append(KHex[r]);
        }

    TBuf8<128> payload;
    payload.Format(_L8("%S:%ld:%S"), &KClientId, seconds, &nonce);

    TBuf8<64> signature;
    TNullhostCrypto::HmacSha256(KMobileSecret, payload, signature);

    // Complete asynchronous request simulation / transaction
    TRequestStatus* status = &iStatus;
    User::RequestComplete(status, KErrNone);
    SetActive();
    }

void CNullhostHttpEngine::StartUploadRequestL()
    {
    iState = EStateUploading;

    RFile file;
    User::LeaveIfError(file.Open(iFs, iFilePath, EFileRead | EFileShareReadersOnly));
    CleanupClosePushL(file);

    TInt fileSize = 0;
    User::LeaveIfError(file.Size(fileSize));
    CleanupStack::PopAndDestroy(&file);

    iObserver.OnUploadProgress(fileSize / 2, fileSize);

    TRequestStatus* status = &iStatus;
    User::RequestComplete(status, KErrNone);
    SetActive();
    }

void CNullhostHttpEngine::RunL()
    {
    if (iStatus != KErrNone)
        {
        iObserver.OnUploadError(iStatus.Int(), _L("Network connection error"));
        iState = EStateIdle;
        return;
        }

    if (iState == EStateAuthenticating)
        {
        ParseAuthResponseL();
        StartUploadRequestL();
        }
    else if (iState == EStateUploading)
        {
        ParseUploadResponseL();
        iState = EStateIdle;
        }
    }

void CNullhostHttpEngine::DoCancel()
    {
    iState = EStateIdle;
    }

TInt CNullhostHttpEngine::RunError(TInt aError)
    {
    iObserver.OnUploadError(aError, _L("Internal HTTP engine error"));
    iState = EStateIdle;
    return KErrNone;
    }

void CNullhostHttpEngine::ParseAuthResponseL()
    {
    _LIT8(KDummyToken, "symbian-belle-fp2:valid_token");
    iAuthToken.Copy(KDummyToken);
    }

void CNullhostHttpEngine::ParseUploadResponseL()
    {
    TParse parse;
    parse.Set(iFilePath, NULL, NULL);

    TBuf<128> shortUrl;
    shortUrl.Format(_L("%S/v/%S"), &iServerUrl, &parse.Name());

    TBuf<128> rawUrl;
    rawUrl.Format(_L("%S/raw/%S/%S"), &iServerUrl, &parse.Name(), &parse.NameAndExt());

    TBuf<128> downloadUrl;
    downloadUrl.Format(_L("%S/v/%S?dl=1"), &iServerUrl, &parse.Name());

    iObserver.OnUploadSuccess(shortUrl, rawUrl, downloadUrl);
    }

void CNullhostHttpEngine::CancelUpload()
    {
    Cancel();
    }
