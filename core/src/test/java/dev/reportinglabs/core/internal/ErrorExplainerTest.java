package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorExplainerTest {

    private static Map<String, Object> x(Throwable t) { return ErrorExplainer.explain(t); }

    @Test void cucumberUndefinedStep() {
        Map<String, Object> e = x(new RuntimeException("The step 'I archive all orders' is undefined.\nYou can implement this step using the snippet(s) below:\n\n@When(\"I archive all orders\")"));
        assertEquals("undefined-step", e.get("kind"));
        assertTrue(String.valueOf(e.get("summary")).startsWith("No step definition matches \"I archive all orders\""), String.valueOf(e.get("summary")));
        Map<String, Object> p = x(new RuntimeException("The step 'I archive all orders' is pending: its step definition throws PendingException."));
        assertEquals("pending-step", p.get("kind"));
    }

    @Test void testngAssertion() {
        Map<String, Object> e = x(new AssertionError("cart total expected [₹ 89.90] but found [₹ 109.90]"));
        assertEquals("assertion", e.get("kind"));
        assertEquals("cart total was wrong: expected ₹ 89.90, got ₹ 109.90.", e.get("summary"));
    }

    @Test void hamcrestAssertion() {
        // assertThat(code, is(200)): the but: line is indented and there is no reason line
        Map<String, Object> e = x(new AssertionError("\nExpected: is <200>\n     but: was <404>"));
        assertEquals("assertion", e.get("kind"));
        assertEquals("The value was wrong: expected <200>, got <404>.", e.get("summary"));
        // assertThat("status code", code, is(200)): the reason names the value
        Map<String, Object> r = x(new AssertionError("status code\nExpected: is <200>\n     but: was <404>"));
        assertEquals("status code was wrong: expected <200>, got <404>.", r.get("summary"));
        // equalTo(): no is/was prefix to strip
        Map<String, Object> q = x(new AssertionError("\nExpected: \"Sign in\"\n     but: was \"Reset\""));
        assertEquals("The value was wrong: expected \"Sign in\", got \"Reset\".", q.get("summary"));
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

    @Test void browserCrashIsItsOwnCategory() {
        assertEquals("crashed", x(new RuntimeException("Error {\n  message='Target crashed'\n  name='Error'\n}")).get("kind"));
        assertEquals("Browser crashed", x(new org.openqa.selenium.WebDriverException("unknown error: session deleted because of page crash")).get("label"));
        assertEquals("closed", x(new RuntimeException("Target page, context or browser has been closed")).get("kind"));
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
