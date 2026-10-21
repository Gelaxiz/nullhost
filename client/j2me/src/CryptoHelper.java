package org.gelaxiz.nullhost;

/**
 * Pure Java CLDC 1.1 SHA-256 and HMAC-SHA-256 implementation.
 * Zero external dependencies. Compatible with all Java ME devices.
 */
public class CryptoHelper {

    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private static int rotr(int x, int n) {
        return (x >>> n) | (x << (32 - n));
    }

    public static byte[] sha256(byte[] data) {
        int[] H = {
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
        };

        long origLenBits = ((long) data.length) * 8L;
        int padLen = (data.length % 64 < 56) ? (56 - data.length % 64) : (120 - data.length % 64);
        byte[] padded = new byte[data.length + padLen + 8];
        System.arraycopy(data, 0, padded, 0, data.length);
        padded[data.length] = (byte) 0x80;

        for (int i = 0; i < 8; i++) {
            padded[padded.length - 1 - i] = (byte) ((origLenBits >>> (i * 8)) & 0xFF);
        }

        int[] w = new int[64];
        for (int offset = 0; offset < padded.length; offset += 64) {
            for (int i = 0; i < 16; i++) {
                int base = offset + i * 4;
                w[i] = ((padded[base] & 0xFF) << 24)
                        | ((padded[base + 1] & 0xFF) << 16)
                        | ((padded[base + 2] & 0xFF) << 8)
                        | (padded[base + 3] & 0xFF);
            }
            for (int t = 16; t < 64; t++) {
                int s0 = rotr(w[t - 15], 7) ^ rotr(w[t - 15], 18) ^ (w[t - 15] >>> 3);
                int s1 = rotr(w[t - 2], 17) ^ rotr(w[t - 2], 19) ^ (w[t - 2] >>> 10);
                w[t] = w[t - 16] + s0 + w[t - 7] + s1;
            }

            int a = H[0];
            int b = H[1];
            int c = H[2];
            int d = H[3];
            int e = H[4];
            int f = H[5];
            int g = H[6];
            int h = H[7];

            for (int t = 0; t < 64; t++) {
                int S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                int ch = (e & f) ^ ((~e) & g);
                int temp1 = h + S1 + ch + K[t] + w[t];
                int S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                int maj = (a & b) ^ (a & c) ^ (b & c);
                int temp2 = S0 + maj;

                h = g;
                g = f;
                f = e;
                e = d + temp1;
                d = c;
                c = b;
                b = a;
                a = temp1 + temp2;
            }

            H[0] += a;
            H[1] += b;
            H[2] += c;
            H[3] += d;
            H[4] += e;
            H[5] += f;
            H[6] += g;
            H[7] += h;
        }

        byte[] result = new byte[32];
        for (int i = 0; i < 8; i++) {
            result[i * 4] = (byte) ((H[i] >>> 24) & 0xFF);
            result[i * 4 + 1] = (byte) ((H[i] >>> 16) & 0xFF);
            result[i * 4 + 2] = (byte) ((H[i] >>> 8) & 0xFF);
            result[i * 4 + 3] = (byte) (H[i] & 0xFF);
        }
        return result;
    }

    public static String hmacSha256Hex(String key, String message) {
        try {
            byte[] kBytes = key.getBytes("UTF-8");
            byte[] mBytes = message.getBytes("UTF-8");

            if (kBytes.length > 64) {
                kBytes = sha256(kBytes);
            }
            byte[] keyPadded = new byte[64];
            System.arraycopy(kBytes, 0, keyPadded, 0, kBytes.length);

            byte[] oKeyPad = new byte[64];
            byte[] iKeyPad = new byte[64];
            for (int i = 0; i < 64; i++) {
                oKeyPad[i] = (byte) (keyPadded[i] ^ 0x5C);
                iKeyPad[i] = (byte) (keyPadded[i] ^ 0x36);
            }

            byte[] innerInput = new byte[64 + mBytes.length];
            System.arraycopy(iKeyPad, 0, innerInput, 0, 64);
            System.arraycopy(mBytes, 0, innerInput, 64, mBytes.length);
            byte[] innerHash = sha256(innerInput);

            byte[] outerInput = new byte[64 + 32];
            System.arraycopy(oKeyPad, 0, outerInput, 0, 64);
            System.arraycopy(innerHash, 0, outerInput, 64, 32);
            byte[] hmac = sha256(outerInput);

            StringBuffer sb = new StringBuffer(64);
            for (int i = 0; i < hmac.length; i++) {
                String hex = Integer.toHexString(hmac[i] & 0xFF);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
