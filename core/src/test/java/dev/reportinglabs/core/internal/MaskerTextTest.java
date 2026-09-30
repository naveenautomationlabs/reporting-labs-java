package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Free-text masking: every shape a log line can take, and what must survive. */
class MaskerTextTest {

    private static final String[] SECRETS = { "Sup3rS3cret!", "secret_sauce", "admin123", "tok-abc-123", "eyJhbGci", "dXNlcjpw",
        "abc123def456", "12345abcde", "AbCdEf123456", "s3cr3t-value", "ABCDEF123456", "abcdef123456", "xyz789", "rt-9988-xx", "abcdefgh", "ghp_1234" };

    private static void assertNoLeak(Masker m, String line) {
        String out = m.maskText(line);
        for (String s : SECRETS) if (line.contains(s)) assertFalse(out.contains(s), "leak in: " + line + " => " + out);
    }

    @Test void keyedShapes() {
        Masker m = new Masker(List.of("cardNumber"));
        for (String line : new String[] {
            "password=Sup3rS3cret!", "password: Sup3rS3cret!", "Password : Sup3rS3cret!", "password:Sup3rS3cret!", "PASSWORD=Sup3rS3cret!",
            "{\"username\":\"a\",\"password\":\"Sup3rS3cret!\"}", "{ \"password\" : \"Sup3rS3cret!\" }", "\"password\"=>\"Sup3rS3cret!\"",
            "LoginRequest{username='a', password='Sup3rS3cret!'}", "Login(user=admin, password=Sup3rS3cret!)",
            "user=admin, pass=admin123", "--password Sup3rS3cret!", "PASSWORD IS Sup3rS3cret!", "Password\tSup3rS3cret!", "password -> Sup3rS3cret!",
            "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
            "Authorization: Basic dXNlcjpwYXNzd29yZA==", "Bearer tok-abc-123",
            "token: tok-abc-123", "token tok-abc-123", "Using token tok-abc-123 for the call", "the token is: tok-abc-123",
            "accessToken=abc123def456", "x-api-key: 12345abcde", "apiKey=AbCdEf123456", "Api key: 12345abcde", "client_secret=s3cr3t-value",
            "https://api.example.com/login?user=a&password=Sup3rS3cret!&x=1", "https://user:Sup3rS3cret!@host.example.com/path",
            "curl -u admin:Sup3rS3cret! https://x", "Cookie: JSESSIONID=ABCDEF123456; token=xyz789", "Set-Cookie: sessionid=abcdef123456; Path=/",
            "The password was Sup3rS3cret! and it worked", "Password for user standard_user is secret_sauce",
            "credentials: standard_user:secret_sauce", "Login with credentials standard_user:secret_sauce",
            "refresh_token = rt-9988-xx", "AUTH_TOKEN=abcdefgh", "auth=Sup3rS3cret!", "x_auth=Sup3rS3cret!",
            "Typed Sup3rS3cret! into password field", "key=ghp_1234567890abcdefghijklmnop",
            "pwd=Sup3rS3cret!", "passwd: Sup3rS3cret!", "db.password=Sup3rS3cret!", "spring.datasource.password=Sup3rS3cret!", "user.password.value=Sup3rS3cret!",
            "password=\"Sup3rS3cret!\"", "passwordConfirm=Sup3rS3cret!", "2026-09-30 10:00:00 INFO LoginTest - password=Sup3rS3cret!",
            "Login successful for admin with pwd Sup3rS3cret!", "Password: Sup3rS3cret!, Token: tok-abc-123", "password=Sup3rS3cret! token=tok-abc-123",
            "Response: {\"access_token\":\"tok-abc-123\",\"expires_in\":3600}", "Passwort=Sup3rS3cret!",
        }) assertNoLeak(m, line);
    }

    @Test void surroundingTextSurvives() {
        Masker m = new Masker();
        assertEquals("https://api.example.com/login?user=a&password=****&x=1", m.maskText("https://api.example.com/login?user=a&password=Sup3rS3cret!&x=1"));
        assertEquals("Login(user=admin, password=****)", m.maskText("Login(user=admin, password=Sup3rS3cret!)"));
        assertEquals("\"password\"=>\"****\"", m.maskText("\"password\"=>\"Sup3rS3cret!\""));
        assertEquals("user.password.value=****", m.maskText("user.password.value=Sup3rS3cret!"));
        assertEquals("Password for user standard_user is ****", m.maskText("Password for user standard_user is secret_sauce"));
        assertEquals("https://user:****@host.example.com/path", m.maskText("https://user:Sup3rS3cret!@host.example.com/path"));
        assertEquals("Bearer ****", m.maskText("Bearer tok-abc-123"));
        // the scheme word is never learned as a secret
        m.maskText("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        assertEquals("Bearer ****", m.maskText("Bearer tok-abc-123"));
        assertEquals("password is valid", m.maskText("password is valid"));
        assertEquals("the author=Naveen", m.maskText("the author=Naveen"));
    }

    @Test void valuesLearnedFromEarlierLinesAreMaskedWithoutAKey() {
        Masker m = new Masker();
        assertEquals("Logging in as standard_user / secret_sauce", m.maskText("Logging in as standard_user / secret_sauce"));   // nothing known yet
        m.maskText("password=secret_sauce");
        assertEquals("Logging in as standard_user / ****", m.maskText("Logging in as standard_user / secret_sauce"));
        assertEquals("sendKeys(****)", m.maskText("sendKeys(secret_sauce)"));
        assertEquals("secret_sauce2 stays", m.maskText("secret_sauce2 stays"));   // word boundary for plain values
    }

    @Test void valuesUnderSensitiveKeysInDataBlocksAreLearned() {
        Masker m = new Masker();
        m.apply(Map.of("username", "standard_user", "password", "Sup3rS3cret!"));
        assertEquals("Typed **** then clicked", m.maskText("Typed Sup3rS3cret! then clicked"));
        assertEquals("standard_user logged in", m.maskText("standard_user logged in"));
    }

    @Test void maskValuesFromConfigAndShortValuesIgnored() {
        Masker m = new Masker(List.of(), List.of("tok-abc-123", "ab"), false);
        assertEquals("got **** back", m.maskText("got tok-abc-123 back"));
        assertEquals("ab is short", m.maskText("ab is short"));
    }
}
