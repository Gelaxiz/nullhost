#ifndef NULLHOSTCRYPTO_H
#define NULLHOSTCRYPTO_H

#include <e32base.h>

class TNullhostCrypto
    {
public:
    static void Sha256(const TDesC8& aData, TDes8& aOutHash);
    static void HmacSha256(const TDesC8& aKey, const TDesC8& aMessage, TDes8& aOutHex);

private:
    static TUint32 Rotr(TUint32 aVal, TUint aBits);
    };

#endif // NULLHOSTCRYPTO_H
