#include "NullhostCrypto.h"

static const TUint32 KSha256K[64] = 
    {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

TUint32 TNullhostCrypto::Rotr(TUint32 aVal, TUint aBits)
    {
    return (aVal >> aBits) | (aVal << (32 - aBits));
    }

void TNullhostCrypto::Sha256(const TDesC8& aData, TDes8& aOutHash)
    {
    TUint32 h[8] = 
        {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };

    TUint origLen = aData.Length();
    TUint64 origBits = (TUint64)origLen * 8;
    TUint padLen = (origLen % 64 < 56) ? (56 - (origLen % 64)) : (120 - (origLen % 64));
    TUint totalLen = origLen + padLen + 8;

    HBufC8* buf = HBufC8::New(totalLen);
    if (!buf) return;
    TPtr8 ptr = buf->Des();
    ptr.Append(aData);
    ptr.Append(TChar(0x80));
    ptr.AppendFill(0x00, padLen - 1);

    for (TInt i = 7; i >= 0; i--)
        {
        ptr.Append((TUint8)((origBits >> (i * 8)) & 0xFF));
        }

    TUint32 w[64];
    for (TUint chunk = 0; chunk < totalLen; chunk += 64)
        {
        const TUint8* p = ptr.Ptr() + chunk;
        for (TInt t = 0; t < 16; t++)
            {
            w[t] = ((TUint32)p[t*4] << 24) | ((TUint32)p[t*4+1] << 16) | ((TUint32)p[t*4+2] << 8) | (TUint32)p[t*4+3];
            }
        for (TInt t = 16; t < 64; t++)
            {
            TUint32 s0 = Rotr(w[t-15], 7) ^ Rotr(w[t-15], 18) ^ (w[t-15] >> 3);
            TUint32 s1 = Rotr(w[t-2], 17) ^ Rotr(w[t-2], 19) ^ (w[t-2] >> 10);
            w[t] = w[t-16] + s0 + w[t-7] + s1;
            }

        TUint32 a = h[0], b = h[1], c = h[2], d = h[3];
        TUint32 e = h[4], f = h[5], g = h[6], hVal = h[7];

        for (TInt t = 0; t < 64; t++)
            {
            TUint32 S1 = Rotr(e, 6) ^ Rotr(e, 11) ^ Rotr(e, 25);
            TUint32 ch = (e & f) ^ ((~e) & g);
            TUint32 temp1 = hVal + S1 + ch + KSha256K[t] + w[t];
            TUint32 S0 = Rotr(a, 2) ^ Rotr(a, 13) ^ Rotr(a, 22);
            TUint32 maj = (a & b) ^ (a & c) ^ (b & c);
            TUint32 temp2 = S0 + maj;

            hVal = g;
            g = f;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
            }

        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hVal;
        }

    delete buf;

    aOutHash.Zero();
    for (TInt i = 0; i < 8; i++)
        {
        aOutHash.Append((TUint8)((h[i] >> 24) & 0xFF));
        aOutHash.Append((TUint8)((h[i] >> 16) & 0xFF));
        aOutHash.Append((TUint8)((h[i] >> 8) & 0xFF));
        aOutHash.Append((TUint8)(h[i] & 0xFF));
        }
    }

void TNullhostCrypto::HmacSha256(const TDesC8& aKey, const TDesC8& aMessage, TDes8& aOutHex)
    {
    TBuf8<64> kPadded;
    kPadded.FillZ(64);

    if (aKey.Length() > 64)
        {
        TBuf8<32> keyHash;
        Sha256(aKey, keyHash);
        kPadded.Copy(keyHash);
        }
    else
        {
        kPadded.Copy(aKey);
        }

    TBuf8<64> oKeyPad;
    TBuf8<64> iKeyPad;
    for (TInt i = 0; i < 64; i++)
        {
        oKeyPad.Append((TUint8)(kPadded[i] ^ 0x5C));
        iKeyPad.Append((TUint8)(kPadded[i] ^ 0x36));
        }

    HBufC8* innerMsg = HBufC8::New(64 + aMessage.Length());
    if (!innerMsg) return;
    TPtr8 innerPtr = innerMsg->Des();
    innerPtr.Append(iKeyPad);
    innerPtr.Append(aMessage);

    TBuf8<32> innerHash;
    Sha256(innerPtr, innerHash);
    delete innerMsg;

    TBuf8<96> outerMsg;
    outerMsg.Append(oKeyPad);
    outerMsg.Append(innerHash);

    TBuf8<32> mac;
    Sha256(outerMsg, mac);

    _LIT8(KHexChars, "0123456789abcdef");
    aOutHex.Zero();
    for (TInt i = 0; i < mac.Length(); i++)
        {
        TUint8 byteVal = mac[i];
        aOutHex.Append(KHexChars[byteVal >> 4]);
        aOutHex.Append(KHexChars[byteVal & 0x0F]);
        }
    }
