package com.blockforge.crypto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class HashUtilTest {

    @Test
    void sha256_isDeterministic() {
        String a = HashUtil.sha256("hello");
        String b = HashUtil.sha256("hello");
        assertEquals(a, b);
    }

    @Test
    void sha256_produces64CharHexString() {
        String hash = HashUtil.sha256("test");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"), "Hash must be lowercase hex");
    }

    @Test
    void sha256_avalancheEffect() {
        String h1 = HashUtil.sha256("hello");
        String h2 = HashUtil.sha256("hellO"); // single char change
        assertNotEquals(h1, h2);
        // At least half the characters should differ (avalanche)
        long diff = 0;
        for (int i = 0; i < h1.length(); i++) if (h1.charAt(i) != h2.charAt(i)) diff++;
        assertTrue(diff > 20, "Expected strong avalanche effect");
    }

    @Test
    void merkleRoot_singleElement() {
        String root = HashUtil.merkleRoot(Collections.singletonList("abc"));
        assertEquals("abc", root);
    }

    @Test
    void merkleRoot_twoElements() {
        String root = HashUtil.merkleRoot(Arrays.asList("a", "b"));
        assertEquals(HashUtil.sha256("ab"), root);
    }

    @Test
    void merkleRoot_emptyList_returnsEmpty() {
        assertEquals("", HashUtil.merkleRoot(Collections.emptyList()));
    }

    @Test
    void merkleRoot_oddNumberOfElements_duplicatesLast() {
        String root3 = HashUtil.merkleRoot(Arrays.asList("a", "b", "c"));
        // level 1: H("ab"), H("cc")   →   root: H(H("ab") + H("cc"))
        String l1a = HashUtil.sha256("ab");
        String l1b = HashUtil.sha256("cc");
        String expected = HashUtil.sha256(l1a + l1b);
        assertEquals(expected, root3);
    }

    @Test
    void miningTarget_returnsCorrectLeadingZeros() {
        assertEquals("000",  HashUtil.miningTarget(3));
        assertEquals("0000", HashUtil.miningTarget(4));
        assertEquals("",     HashUtil.miningTarget(0));
    }
}
