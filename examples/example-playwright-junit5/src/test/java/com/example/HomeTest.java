package com.example;

import com.microsoft.playwright.*;
import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.annotations.*;
import dev.reportinglabs.playwright.RlPlaywright;
import org.junit.jupiter.api.*;

/**
 * How Playwright for Java tests look with reportingLabs.
 *
 * The only reportingLabs-specific line is {@code RlPlaywright.attach(page)} —
 * every request is then recorded, a trace is saved, and a failure screenshot
 * is attached automatically.
 */
@Owner("naveen") @Feature("home")
class HomeTest {

    Playwright playwright;
    Browser browser;
    Page page;

    @BeforeEach
    void setup() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        RlPlaywright.attach(page);   // <-- one-line auto-capture
    }

    @AfterEach
    void tearDown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test @Priority("P0") @Severity("blocker") @Story("SHOP-001")
    void loads_the_home_page() {
        Rl.log("navigating to example.com");
        page.navigate("https://example.com");
        Assertions.assertTrue(page.title().contains("Example"));
    }

    @Test @Priority("P1") @Severity("major") @Issue("SHOP-042")
    void search_returns_results() {
        page.setContent("<html><body><h1>Results</h1><ul><li>result 1</li></ul></body></html>");
        Rl.log("checking results");
        Assertions.assertEquals(1, page.locator("li").count());
    }
}
