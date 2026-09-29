package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorExplainerTest {

    private static Map<String, Object> x(Throwable t) { return ErrorExplainer.explain(t); }

    @Test void testngAssertion() {
        Map<String, Object> e = x(new AssertionError("cart total expected [₹ 89.90] but found [₹ 109.90]"));
        assertEquals("assertion", e.get("kind"));
        assertEquals("cart total was wrong: expected ₹ 89.90, got ₹ 109.90.", e.get("summary"));
    }

    @Test void junitAssertion() {
        Map<String, Object> e = x(new AssertionError("title ==> expected: <Sign in> but was: <Reset your password>"));
        assertEquals("assertion", e.get("kind"));
        assertTrue(String.valueOf(e.get("summary")).contains("expected Sign in, got Reset your password"), String.valueOf(e.get("summary")));
    }

    @Test void playwrightAssertionWithLocator() {
        String m = "Locator expected to have text\nExpected: Welcome back, Naveen\nReceived: Welcome back, demo\n\nCall log:\n  - Assert \"hasText\" locator(\"h1\") with timeout 1200ms\n  - waiting for locator(\"h1\")\n";
        Map<String, Object> e = x(new AssertionError(m));
        assertEquals("assertion", e.get("kind"));
        assertEquals("locator(\"h1\")", e.get("locator"));
        assertTrue(String.valueOf(e.get("summary")).startsWith("locator(\"h1\") had the wrong text: expected Welcome back, Naveen, got Welcome back, demo."), String.valueOf(e.get("summary")));
    }

    static class TimeoutError extends RuntimeException { TimeoutError(String m) { super(m); } }

    @Test void playwrightJavaTimeoutUsesHints() {
        // Playwright for Java's TimeoutError carries no call log; the stack and the source line supply action and locator.
        Throwable t = new com.microsoft.playwright.TimeoutError("Timeout 1500ms exceeded.");
        Map<String, Object> e = ErrorExplainer.explain(t, "locator.click", "locator(\"#apply-coupon\")");
        assertEquals("not-found", e.get("kind"));
        assertEquals("locator(\"#apply-coupon\") was not ready within 1.5s, so locator.click could not run.", e.get("summary"));
        Map<String, Object> nav = ErrorExplainer.explain(new com.microsoft.playwright.TimeoutError("Timeout 30000ms exceeded."), "page.navigate", null);
        assertEquals("navigation", nav.get("kind"));
    }

    @Test void playwrightWrappedMessageIsUnwrapped() {
        String raw = "Error {\n  message='net::ERR_CONNECTION_REFUSED at http://localhost:1/\nCall log:\n  - navigating to \"http://localhost:1/\", waiting until \"load\"\n\n  name='Error\n  stack='Error: net::ERR_CONNECTION_REFUSED at http://localhost:1/\n}";
        assertEquals("net::ERR_CONNECTION_REFUSED at http://localhost:1/\nCall log:\n  - navigating to \"http://localhost:1/\", waiting until \"load\"", ErrorExplainer.unwrap(raw));
        Map<String, Object> e = x(new RuntimeException(raw));
        assertEquals("network", e.get("kind"));
        assertEquals("http://localhost:1/", e.get("url"));
    }

    @Test void restAssuredAssertions() {
        Map<String, Object> e = x(new AssertionError("1 expectation failed.\nJSON path status doesn't match.\nExpected: SHIPPED\n  Actual: DELIVERED\n"));
        assertEquals("JSON path status doesn't match: expected SHIPPED, got DELIVERED.", e.get("summary"));
        Map<String, Object> s = x(new AssertionError("1 expectation failed.\nExpected status code <201> but was <404>.\n"));
        assertEquals("Expected status code <201> but was <404>.", s.get("summary"));
        Map<String, Object> tm = x(new AssertionError("1 expectation failed.\nExpected response time was not a value less than <300L> milliseconds, was 1216 milliseconds (1216 ms).\n"));
        assertEquals("The response took 1216 ms, more than the test allows.", tm.get("summary"));
    }

    @Test void nullPointerIsScript() {
        Map<String, Object> e = x(new NullPointerException("Cannot invoke \"String.length()\" because \"name\" is null"));
        assertEquals("script", e.get("kind"));
    }

    @Test void seleniumNoSuchElement() {
        Map<String, Object> e = x(new org.openqa.selenium.NoSuchElementException("no such element: Unable to locate element: {\"method\":\"css selector\",\"selector\":\"#apply\\-coupon\"}\n  (Session info: chrome=141)"));
        assertEquals("not-found", e.get("kind"));
        assertEquals("#apply-coupon", e.get("locator"));
    }

    @Test void displayMessage() {
        assertEquals("cart total expected [1] but found [2]", ErrorExplainer.displayMessage(new AssertionError("cart total expected [1] but found [2]")));
        assertEquals("NullPointerException", ErrorExplainer.displayMessage(new NullPointerException()));
        assertEquals("IllegalStateException: boom", ErrorExplainer.displayMessage(new IllegalStateException("boom")));
    }

    @Test void locatorFromSourceLine() {
        assertEquals("locator(\"#apply-coupon\")", SourceLocator.locatorIn("        page().locator(\"#apply-coupon\").click(new Locator.ClickOptions().setTimeout(1500));"));
        assertEquals("getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"Pay\")).first()", SourceLocator.locatorIn("page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(\"Pay\")).first().click();"));
        assertEquals("\"#submit\"", SourceLocator.locatorIn("        page().click(\"#submit\");"));
        assertNull(SourceLocator.locatorIn("        Thread.sleep(3000);"));
    }
}
