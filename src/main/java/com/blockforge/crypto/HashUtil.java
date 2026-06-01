package com.blockforge.crypto;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Cryptographic hashing utilities used throughout the chain. */
public final class HashUtil {

    private HashUtil() {}

    /** Returns the SHA-256 hex digest of the input string. */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[]        bytes  = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hex    = new StringBuilder(64);
            for (byte b : bytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /**
     * Builds a Merkle root from a list of leaf hashes.
     *
     * Pairs are hashed upward until one root remains. An odd number of leaves
     * at any level causes the last leaf to be duplicated — matching Bitcoin's
     * construction.
     */
    public static String merkleRoot(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) return "";

        List<String> layer = new ArrayList<>(hashes);
        while (layer.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                String left  = layer.get(i);
                String right = (i + 1 < layer.size()) ? layer.get(i + 1) : left; // duplicate if odd
                next.add(sha256(left + right));
            }
            layer = next;
        }
        return layer.get(0);
    }

    /** Returns a string of `difficulty` zeros — the proof-of-work target prefix. */
    public static String miningTarget(int difficulty) {
        return new String(new char[difficulty]).replace('\0', '0');
    }
}
