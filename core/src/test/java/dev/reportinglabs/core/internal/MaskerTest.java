package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MaskerTest {
    private final Masker m = new Masker(Arrays.asList("pan"));
    private static final String PW = "S3cret@123", TOK = "tok_9f8e7d6c5b4a";

    private void masked(String in) {
        String out = m.maskText(in);
        assertFalse(out.contains(PW) || out.contains(TOK), in + "  ->  " + out);
        assertTrue(out.contains("****"), in + "  ->  " + out);
    }

    @Test void keyValueForms() {
        masked("password=" + PW);
        masked("Password: " + PW);
        masked("{user=demo, password=" + PW + ", apiKey=" + TOK + "}");
        masked("{\"password\":\"" + PW + "\",\"access_token\":\"" + TOK + "\"}");
        masked("X-Api-Key: " + TOK);
        masked("userPassword -> " + PW);
        masked("db.password = " + PW);
        masked("pan: 4111111111111111".replace("4111111111111111", TOK));
    }

    @Test void spokenForms() {
        masked("Logging in with password " + PW);
        masked("the password is " + PW);
        masked("token was '" + TOK + "'");
        masked("otp is 482913");
    }

    @Test void knownFormats() {
        masked("Authorization: Bearer " + TOK);
        assertEquals("jwt ****", m.maskText("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcDEFghiJKLmno"));
        assertEquals("key ****", m.maskText("key AKIAIOSFODNN7EXAMPLE"));
    }

    @Test void assertionValues() {
        String s = m.maskText("token should match expected [other] but found [" + TOK + "]");
        assertFalse(s.contains(TOK), s);
        // No secret word in the text: compared values stay readable.
        assertEquals("expected [Sign in] but found [Reset your password]", m.maskText("expected [Sign in] but found [Reset your password]"));
    }

    @Test void leavesOrdinaryTextAlone() {
        for (String s : new String[] { "password is invalid", "author is naveen", "pinned: true", "token refresh failed",
                "Expected: \"Reset your password\" Received: \"Sign in\"", "password field is visible", "the pin code screen" }) {
            assertEquals(s, m.maskText(s));
        }
        assertNull(m.maskText(null));
    }

    @Test void codeLiteralsAndMapPairs() {
        masked("page().fill(\"#password\", \"" + PW + "\");");
        masked("Map.of(\"user\", \"demo\", \"password\", \"" + PW + "\")");
        assertEquals("assertEquals(label, \"Password\")", m.maskText("assertEquals(label, \"Password\")"));
    }

    @Test void apiBodies() {
        assertEquals("{\"password\":\"****\",\"user\":\"demo\"}", m.maskText("{\"password\":\"" + PW + "\",\"user\":\"demo\"}"));
    }

    @Test void applyMasksStringsInsideStructures() {
        Object out = m.apply(java.util.Collections.singletonMap("note", "password=" + PW));
        assertEquals("{note=password=****}", out.toString());
    }

    @Test void cardNumbersAndCardKeys() {
        Masker m = new Masker(java.util.Collections.emptyList());
        assertEquals("{\"sku\":\"A\",\"cardNumber\":\"****\"}", m.maskText("{\"sku\":\"A\",\"cardNumber\":\"4111111111111111\"}"));
        assertEquals("paid with **** today", m.maskText("paid with 4111 1111 1111 1111 today"));
        assertEquals("order 1234567890123 shipped", m.maskText("order 1234567890123 shipped"));   // fails Luhn: left alone
        assertEquals("pan=****", m.maskText("pan=5555555555554444"));
    }
}
