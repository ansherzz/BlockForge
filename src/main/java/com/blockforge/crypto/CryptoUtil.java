package com.blockforge.crypto;

import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/** ECDSA signing and verification utilities. Requires the BouncyCastle provider. */
public final class CryptoUtil {

    private CryptoUtil() {}

    /** Signs `data` with the given private key using ECDSA. */
    public static byte[] sign(PrivateKey privateKey, String data) {
        try {
            Signature sig = Signature.getInstance("ECDSA", "BC");
            sig.initSign(privateKey);
            sig.update(data.getBytes("UTF-8"));
            return sig.sign();
        } catch (Exception e) {
            throw new RuntimeException("ECDSA signing failed", e);
        }
    }

    /** Returns true if `signature` was produced by the private key matching `publicKey`. */
    public static boolean verify(PublicKey publicKey, String data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("ECDSA", "BC");
            sig.initVerify(publicKey);
            sig.update(data.getBytes("UTF-8"));
            return sig.verify(signature);
        } catch (Exception e) {
            throw new RuntimeException("ECDSA verification failed", e);
        }
    }

    /** Base64-encodes a public or private key for use in hashes or display. */
    public static String encodeKey(Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
