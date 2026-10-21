#ifndef NULLHOSTHTTPENGINE_H
#define NULLHOSTHTTPENGINE_H

#include <e32base.h>
#include <f32file.h>

class MNullhostHttpObserver
    {
public:
    virtual void OnUploadProgress(TInt aBytesSent, TInt aTotalBytes) = 0;
    virtual void OnUploadSuccess(const TDesC& aUrl, const TDesC& aRawUrl, const TDesC& aDownloadUrl) = 0;
    virtual void OnUploadError(TInt aErrorCode, const TDesC& aErrorMessage) = 0;
    };

class CNullhostHttpEngine : public CActive
    {
public:
    static CNullhostHttpEngine* NewL(MNullhostHttpObserver& aObserver);
    ~CNullhostHttpEngine();

    void AuthenticateAndUploadL(
        const TDesC& aServerUrl,
        const TDesC& aFilePath,
        const TDesC& aExpiry,
        const TDesC& aBurnViews,
        const TDesC& aPassword,
        const TDesC& aCustomSlug,
        const TDesC& aAccountId);

    void CancelUpload();

protected:
    void DoCancel();
    void RunL();
    TInt RunError(TInt aError);

private:
    CNullhostHttpEngine(MNullhostHttpObserver& aObserver);
    void ConstructL();

    void StartAuthRequestL();
    void StartUploadRequestL();
    void ParseAuthResponseL();
    void ParseUploadResponseL();

private:
    enum THttpState
        {
        EStateIdle,
        EStateAuthenticating,
        EStateUploading
        };

    MNullhostHttpObserver& iObserver;
    THttpState iState;
    RFs iFs;

    TFileName iServerUrl;
    TFileName iFilePath;
    TBuf<16> iExpiry;
    TBuf<16> iBurnViews;
    TBuf<64> iPassword;
    TBuf<64> iCustomSlug;
    TBuf<32> iAccountId;

    TBuf8<128> iAuthToken;
    TInt64 iTimeOffset;

    HBufC8* iResponseBody;
    };

#endif // NULLHOSTHTTPENGINE_H
