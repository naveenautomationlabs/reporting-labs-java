# reportingLabs — Java

[![Maven Central](https://img.shields.io/maven-central/v/dev.reportinglabs/reporting-labs-core.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/dev.reportinglabs)
[![Docs](https://img.shields.io/badge/docs-reportinglabs.dev-1A56DB.svg)](https://reportinglabs.dev)

Turn a Java test run into **one HTML file** you can share. No server, no login, no expiry. Open it in a browser, attach it to a Jira ticket, drop it in Slack — it just works.

Seven Maven artifacts, all under the `dev.reportinglabs` groupId. Pick the one for your test framework, then add the one for your tool:

| Artifact | What it is |
|---|---|
| `reporting-labs-testng` | Framework binding for TestNG. Listener found through ServiceLoader, nothing to register |
| `reporting-labs-junit5` | Framework binding for JUnit 5. Extension found through the JUnit Platform, one property. Parameterized, repeated, dynamic (`@TestFactory`, Karate), nested, inherited, disabled, assumptions, time-outs, tags, `TestReporter` entries, hook failures, junit-pioneer retries |
| `reporting-labs-selenium` | Zero code. Finds the WebDriver on your test (fields, base class, page objects, factory, `ThreadLocal`), records every open, click and type as a step, screenshot per policy. Appium drivers too |
| `reporting-labs-playwright` | Zero code. Finds the `Page`, `BrowserContext`, `Browser` or `APIRequestContext` on your test the same way: every action as a step, trace, screenshot and video per policy for UI tests, every request and response for API tests |
| `reporting-labs-rest-assured` | Zero code. Registers a recording filter; every request lands in the API tab with headers, bodies, status and timing |
| `reporting-labs-cucumber` | One property. A row per scenario at its feature-file line, Given/When/Then as steps, tags as filters. TestNG runner or JUnit Platform engine |
| `reporting-labs-core` | Engine, annotations, `Rl.*` helpers. Comes with the bindings; use it alone from plain code |

Every port (Node.js, Java, Python) renders the same HTML template. A Java team's report is byte-for-byte the report a JavaScript or Python team opens.

## Install

Step by step, with screenshots, per tool: [Selenium](https://reportinglabs.dev/get-started/java/selenium) · [Playwright](https://reportinglabs.dev/get-started/java/playwright) · [REST Assured](https://reportinglabs.dev/get-started/java/rest-assured) · [Cucumber](https://reportinglabs.dev/get-started/java/cucumber) · [Other tools](https://reportinglabs.dev/get-started/java/other-tools).

**TestNG:**

```xml
<dependency>
  <groupId>dev.reportinglabs</groupId>
  <artifactId>reporting-labs-testng</artifactId>
  <version>0.1.25</version>
  <scope>test</scope>
</dependency>
```

Nothing to register: the listener is found through ServiceLoader. If you keep a `<listeners>` block in `testng.xml`, `dev.reportinglabs.testng.ReportingLabsListener` can go there too.

**JUnit 5:**

```xml
<dependency>
  <groupId>dev.reportinglabs</groupId>
  <artifactId>reporting-labs-junit5</artifactId>
  <version>0.1.25</version>
  <scope>test</scope>
</dependency>
```

Then one line in `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

**Your tool** (same version, `test` scope): `reporting-labs-selenium`, `reporting-labs-rest-assured`, `reporting-labs-playwright` or `reporting-labs-cucumber`.

Run `mvn test`. Open `target/reporting-labs/index.html` (Gradle: `build/reporting-labs/index.html`).

## What the report shows

- Every test with its real source line (`OrdersApiTest.java:42`); on failure the failing line, a code snippet and a plain-language reading of the error (element not found, assertion with expected/actual, site unreachable, test timed out, hook failed), for Playwright, Selenium, TestNG, JUnit and AssertJ errors.
- Before/After hooks with timings, `Rl.step()` groups, Selenium and Playwright actions, `System.out` / `System.err` lines, retries grouped as attempts and marked flaky, DataProvider rows as Parameters.
- API calls with headers, bodies and Copy as cURL. Screenshots, traces and videos per policy.
- Secrets masked everywhere: headers, bodies, log lines, console output, data blocks, error messages, even `"password", "x"` literals in a code snippet. The masker remembers every value it has masked (and the values of `PASSWORD` / `API_TOKEN` / `*_SECRET` environment variables), so a secret that later appears with no key at all (`Logging in as admin / s3cret`) is blanked too. `reporting-labs.maskValues` adds values it cannot know about.
- Trend, new vs known failures, flaky history and got-slower across runs, from `reporting-labs.history.json`.
- One lane per worker thread on the Timeline.

## Zero code: how the add-ons find your objects

The framework binding hands the test instance to every add-on on the classpath when a test starts and after each `@Before*` / `@After*` hook. The add-on looks for its objects there and wires them; your code does not change. Cucumber has no test instance to hand over, so the plugin hands over each glue class that runs and the add-ons search the static holders it reaches, which is where a Cucumber framework keeps its driver.

| Where it looks | Example |
|---|---|
| Fields of the test class and its base classes | `protected Page page;` in `BaseTest` |
| Page objects and factories held in fields, three levels deep | `pf.page`, `loginPage.driver` |
| `ThreadLocal` holders, instance or static | `DriverFactory.tlDriver` |
| Static fields of the classes the test refers to | a `DriverManager` the test only calls as `DriverManager.getDriver()` |
| Lists and maps of the above | `List<Page> tabs` |

What it does with them:

- Selenium: the `WebDriver` is wrapped with a step recorder and the field is pointed at the wrapper, so page objects built from it record too. Concrete-typed fields (`ChromeDriver driver`) keep the raw driver; screenshots still work. When several drivers are in reach (the test's own plus a quit one a factory `ThreadLocal` never cleared) the screenshot comes from the one the test used, and a driver without a session is skipped.
- Playwright: a `Page` gets every action as a step (read back from Playwright's own trace at the end of the test and filed under the `Rl.step()` block, hook or Gherkin step that was running at the time, so nothing is wrapped and `assertThat(page)` keeps working), a trace and a screenshot per policy; a `BrowserContext` covers its current and future pages; a `Browser` covers every context and is instrumented so pages created inside the test body are attached; an `APIRequestContext` field is swapped for a recording wrapper, so API tests get the API tab. The page's own network traffic (fonts, images, scripts) is not recorded as API calls, same as the Node.js reporter; the trace has it.

If an object lives somewhere the scan cannot reach (a local variable in a helper, a class outside your own packages) attach it by hand once: `RlSelenium.attach(driver)`, `RlPlaywright.attach(page)`, `RlPlaywright.attach(context)`, `RlPlaywright.record(apiContext)`. Attaching an object the scan already found is harmless. `reporting-labs.selenium.autoAttach=false` / `reporting-labs.playwright.autoAttach=false` turn the discovery off.

## Selenium: zero code

Add `reporting-labs-selenium`. Your `BaseTest`, `DriverFactory` and page objects stay as they are: the WebDriver is found on the test instance (a field, a base class, a `ThreadLocal`, a page object, a static `DriverManager`) and wrapped with a step recorder: `open`, `click`, `type` (password fields as ••••), `clear`, `submit`, navigation, alerts, frame and window switches, `run script`, `perform actions`, with the failing action in red and nested locators as `css selector: .row -> tag name: button`. `RlSelenium.attach(driver)` remains for a driver kept out of sight, `RlSelenium.screenshot("name.png")` for an extra screenshot mid-test. Appium's `AndroidDriver` / `IOSDriver` are found the same way.

## Playwright for Java: zero code

Add `reporting-labs-playwright`. Your `BaseTest`, `PlaywrightFactory` and page objects stay as they are: the `Page` (or `BrowserContext`, `Browser`, `APIRequestContext`) is found on the test instance, in a base class, a page object, a factory or a `ThreadLocal`, static holders included, and wired for steps, trace and screenshot per policy: every `navigate`, `fill`, `click` and `expect` as a timed step with the failing one in red, values typed into password fields as ••••. An `APIRequestContext` field is swapped for a recording wrapper, so API tests get the API tab with every request and response. Page traffic (fonts, images, scripts) stays out of the API tab.

Two things still take a line, because the object never sits on the test:

```java
context = browser.newContext(RlPlaywright.contextOptions());   // video: Playwright decides at context creation
APIRequestContext api = RlPlaywright.record(page.request());   // page.request() inside a test body
```

`RlPlaywright.attach(page)` / `attach(context)` remain for a page kept somewhere the discovery cannot see. `reporting-labs.playwright.autoAttach=false` turns the discovery off.

## REST Assured: zero code

Add `reporting-labs-rest-assured`. The recording filter goes into `RestAssured.filters()` when the run starts and is put back at every lifecycle point, so a `RestAssured.reset()` or `replaceFiltersWith(...)` in a hook is fine (inside a test body it drops the filter for the rest of that test; add `RestAssured.filters(new RlRestAssuredFilter())` after it if you must). Query and path params resolved, form fields and multipart part names, text bodies up to 200 KB, binary types as a placeholder, failed requests with status 0. `reporting-labs.restassured.autoRecord=false` turns it off.

## Cucumber JVM: one property

Add `reporting-labs-cucumber` and register the plugin once:

```properties
# src/test/resources/cucumber.properties (TestNG runner, JUnit 4 runner, CLI)
# src/test/resources/junit-platform.properties (JUnit Platform engine)
cucumber.plugin=dev.reportinglabs.cucumber.ReportingLabsPlugin
```

Every scenario is one row named after the scenario, at `orders.feature:13`, with the Gherkin steps (Background included) as steps, `@Before`/`@After` hooks in the hook groups, data tables and doc strings as data blocks, Scenario Outline rows titled with their example values. Tags become filters: `@P1` is the priority, `@blocker` the severity, `@owner:naveen` an owner chip, everything else a tag. An undefined step points at the feature line, and the steps after a failure show as "not run". With the TestNG runner add `reporting-labs-testng` as usual; with the JUnit Platform engine the plugin alone is enough. A Selenium driver or Playwright page kept in a static factory by your hooks is found through the glue classes, its actions land under the Gherkin step that made them, and the screenshot is taken before the `@After` hooks that quit it; REST Assured calls are recorded from any step. `scenario.attach(...)` and `scenario.log(...)` land on the row too. A step matching two definitions is pointed out on the feature line like an undefined one; a scenario aborted by an assumption is skipped with the reason. Works with the TestNG runner, the JUnit Platform engine and the JUnit 4 runner.

## A typical framework, unchanged

This is the shape most Java suites have. Nothing in it mentions reportingLabs, and it produces the full report: hooks with timings, every action as a step, the failing line and snippet, `failure.png` and `trace.zip` on the failed test.

```java
public class BaseTest {
    protected PlaywrightFactory pf;
    protected Page page;
    protected LoginPage loginPage;

    @Parameters({"browser", "headless"})
    @BeforeMethod
    public void setUp(@Optional("chromium") String browser, @Optional("true") String headless) {
        pf = new PlaywrightFactory();
        page = pf.initBrowser(browser, Boolean.parseBoolean(headless));
        loginPage = new LoginPage(page);
    }

    @AfterMethod
    public void tearDown() { pf.tearDown(); }
}

public class LoginTest extends BaseTest {
    @Test
    public void validLoginTest() {
        InventoryPage inventory = loginPage.doLogin("standard_user", "secret_sauce");
        Assert.assertEquals(inventory.getHeaderText(), "Products");
    }
}
```

Swap `Page` for `WebDriver` and `PlaywrightFactory` for `DriverFactory` and the same holds for Selenium.

## Add detail from the test

```java
import dev.reportinglabs.core.Rl;
import dev.reportinglabs.core.annotations.*;
import org.junit.jupiter.api.Test;
import java.util.Map;

@Owner("naveen") @Feature("checkout")
class CheckoutTest {

  @Test @Priority("P0") @Severity("blocker") @Story("SHOP-231")
  void places_an_order_with_a_saved_card() {
    Rl.testData("Cart snapshot", Map.of(
      "user", "demo@shop.io", "card", "4242…", "total", 99.90));
    Rl.log("opening checkout");
    Rl.step("pay", () -> checkout.payWithSavedCard());
    // your existing Selenium / Playwright / REST Assured code, no wrappers
  }
}
```

## Runtime helpers

For everything you know at runtime, use `Rl` — same surface across every framework binding:

| Method | What it does |
|---|---|
| `Rl.log(msg)` | Log line with a timestamp in the test detail |
| `Rl.step(title, body)` | Groups whatever runs inside as a step with timing; nests; returns a value with the `Supplier` overload |
| `Rl.testData(name, obj)` | Pinned data block: a `Map` as key/value, a `List` of `Map`s or CSV text as a table; sensitive keys masked |
| `Rl.api(method, url, status, …)` | Record an HTTP call by hand (automatic with the Selenium, REST Assured and Playwright add-ons) |
| `Rl.attach(name, mime, bytes)` | Any file: screenshot, video, PDF, JSON |
| `Rl.meta(key, value)` | Add a chip to the current test |
| `Rl.shouldCaptureScreenshot()` | The capture policy applied to the current test's outcome, for base classes that take their own screenshots |

## Annotations

Put on a test method or class; method-level wins. All under `dev.reportinglabs.core.annotations`.

| Annotation | Example | Effect |
|---|---|---|
| `@Priority` | `@Priority("P0")` | Sort order in **Needs attention** and charts |
| `@Severity` | `@Severity("blocker")` | Secondary sort, shown as a chip |
| `@Owner` | `@Owner("naveen")` | Owner rollup + Owner leaderboard chart |
| `@Feature` | `@Feature("checkout")` | Feature rollup + Feature × project heatmap |
| `@Story` | `@Story("SHOP-231")` | Linkable chip (via `reporting-labs.links.story`) |
| `@Epic` / `@Issue` | | Linkable chips |
| `@Component` / `@Team` | | Free-form chips |
| `@Meta` | `@Meta(key="region", value="apac")` | Any custom key; repeatable |

## Configuration

Pass as system properties on the command line:

```bash
mvn test \
  -Dreporting-labs.title="Nightly regression" \
  -Dreporting-labs.outputFolder=target/reporting-labs \
  -Dreporting-labs.project.name="ShopLite Web" \
  -Dreporting-labs.metadata.env=staging \
  -Dreporting-labs.metadata.build=ci-4287
```

Or once, in `src/test/resources/reporting-labs.properties`:

```properties
reporting-labs.title=Nightly regression
# your logo in the header: a file in src/test/resources (embedded), an https URL or a data URI
reporting-labs.logo=logo.png
reporting-labs.project.name=ShopLite Web
# the env chip. Resolved in this order: -Dreporting-labs.metadata.env or REPORTING_LABS_METADATA_ENV,
# the variable reporting-labs.envVar names, then -Denv / ENV / TEST_ENV / APP_ENV / any *_ENV variable,
# then this value. A runtime value beats the file, as for every key.
reporting-labs.metadata.env=staging
reporting-labs.links.story=https://shoplite.atlassian.net/browse/{id}
reporting-labs.maskKeys=otp,pan

# Selenium: never | on-failure | always | only-on-pass
reporting-labs.selenium.screenshot=on-failure

# Playwright
reporting-labs.playwright.steps=true
reporting-labs.playwright.screenshot=on-failure
# off by default: snapshots cost about 70 ms per short test
reporting-labs.playwright.trace=on-failure
reporting-labs.playwright.video=never

# REST Assured
reporting-labs.restassured.autoRecord=true

# Cucumber: style Given / When / Then as Gherkin
reporting-labs.bdd=true
```

The plain `reporting-labs.screenshot` / `trace` / `video` keys are the defaults for a tool without its own setting and for `Rl.shouldCaptureScreenshot()` in your own base classes. Comments go on their own line; `java.util.Properties` has no inline comments. A label with a space needs `\ ` (`reporting-labs.env.App\ version=2.4.0`).

Full reference at [reportinglabs.dev](https://reportinglabs.dev/reference/options).

## Works with what you already use

reportingLabs sits on the framework's `@Test` lifecycle — it does not care what happens inside the test body. Same package works for:

- Selenium and Appium (`reporting-labs-selenium`)
- REST Assured (`reporting-labs-rest-assured`)
- Playwright for Java (`reporting-labs-playwright`)
- Cucumber JVM (`reporting-labs-cucumber`), on the TestNG runner, the JUnit Platform engine or the JUnit 4 runner; Karate through its JUnit 5 runner (one row per scenario, nothing extra to add)
- Plain code, HttpClient, JDBC: `Rl.api()` and `Rl.testData()` by hand

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Rows show hooks only: no screenshot, no API tab, no trace | The tool add-on is not on the test classpath, or it is older than 0.1.15, which needed `RlPlaywright.attach(page)` | Add `reporting-labs-selenium` / `-playwright` / `-rest-assured` at the same version as the binding |
| Page exists but nothing is recorded | The page is a local variable, or the holder class is outside your packages | `RlPlaywright.attach(page)` once after creating it |
| Cucumber: no Selenium steps or screenshot on a scenario | The driver sits in an instance field of a step class, not in a static holder the glue classes reach | `RlSelenium.attach(driver)` once, or keep it in a `ThreadLocal` in your factory |
| JUnit 5: `Rl.log` / `System.out` missing on a `@Timeout(threadMode = SEPARATE_THREAD)` test | The body ran on another thread | Use the default same-thread mode, or log from the test thread |
| REST Assured calls missing after `RestAssured.reset()` in a test body | The reset dropped the global filter for the rest of that test | `RestAssured.filters(new RlRestAssuredFilter())` right after it; a reset in a hook needs nothing |
| No video | Playwright decides at context creation | `browser.newContext(RlPlaywright.contextOptions())` and `reporting-labs.playwright.video=on-failure` |
| `page.request()` calls missing from the API tab | The context is created inside the test body | `RlPlaywright.record(page.request())` and use the wrapper |
| Cucumber rows titled "Runs Cucumber Scenarios" | The plugin is not registered | `cucumber.plugin=dev.reportinglabs.cucumber.ReportingLabsPlugin` in `cucumber.properties` (TestNG runner) or `junit-platform.properties` (JUnit Platform engine) |
| Environment row split, `Test` = `data=…` | A space in the properties key | `reporting-labs.env.Test\ data=…` (0.1.15 also repairs the common case) |
| Logo missing from the header | `reporting-labs.logo` points at a file that is not on the path or the test classpath (a warning names what was looked for) | Put `logo.png` in `src/test/resources` and set `reporting-labs.logo=logo.png`, or use an https URL |
| Screenshot appears twice | Your `@AfterMethod` attaches one too | Keep either; an attachment named `screen.png` / `failure.png` from your hook replaces the automatic one |

## Requirements

- JDK 11+
- Maven 3.9+ or Gradle 8+
- JUnit Jupiter 5.10+ or TestNG 7.5+
- Selenium 4.x (Appium Java client 8+), Playwright for Java 1.47+, REST Assured 4.x to 6.x, Cucumber JVM 7.x (each only for its add-on)

## Other languages

The same report, from any stack, because every port renders one shared HTML template.

- **Node.js** — `npm i -D reporting-labs` for Playwright. Source: [reporting-labs](https://github.com/naveenautomationlabs/reporting-labs). Guides: [reportinglabs.dev/get-started/nodejs](https://reportinglabs.dev/get-started/nodejs).
- **Python** — `pip install reporting-labs` for pytest, Playwright, Selenium and Robot Framework. Source: [reporting-labs-python](https://github.com/naveenautomationlabs/reporting-labs-python). Guides: [reportinglabs.dev/get-started/python](https://reportinglabs.dev/get-started/python).

## Release notes

Every version is listed with its changes at [github.com/naveenautomationlabs/reporting-labs-java/releases](https://github.com/naveenautomationlabs/reporting-labs-java/releases). Bugs and requests: [issues](https://github.com/naveenautomationlabs/reporting-labs-java/issues).

## Contributing

See [PUBLISHING.md](./PUBLISHING.md) for the release runbook.

## Security & privacy

reportingLabs is a library that runs inside your own test run. There is no reportingLabs server, account, API key, telemetry or licence check.

- **Nothing is sent anywhere.** The reporter makes no network requests of its own; its only traffic is the traffic your tests already make. An opened report makes no external requests either: fonts, scripts and the logo are embedded, so it works offline and behind a firewall. The only exceptions are opt-in (`reporting-labs.embedFonts=false`, a logo given as an `https://` URL) or need a click (CI, commit and issue links).
- **Everything stays on your machine:** the report folder (`target/reporting-labs/` in Maven projects) holds `index.html`, `report.json`, `report.pdf` and `assets/`, plus the run history `reporting-labs.history.json` next to your project. Nothing is written anywhere else. Whoever can read your test artifacts can read the report; deleting them deletes the data.
- **Secrets are masked before anything is written:** passwords, tokens, cookies, auth headers, API keys, JWTs and card numbers in logs, API bodies and headers, test data, errors and step titles. Selenium masks values typed into password fields. With Playwright for Java, a value passed to `fill()` shows in the step title unless it is a known secret, so read test passwords from environment variables (masked by default) or list them in `reporting-labs.maskValues`.
- **Screenshots, videos and traces are not masked.** They are images and recordings of the application, so run tests against test data, or turn them off for suites that show real personal data.
- **`reporting-labs-core` has no runtime dependencies;** the add-ons declare Selenium, Playwright, REST Assured and Cucumber as `provided`, so they use the versions already in your build. No install or post-install scripts. MIT licensed.

Full details for security reviewers and client projects, including what is read, what is written and what to tell a client: [reportinglabs.dev/security-privacy](https://reportinglabs.dev/security-privacy). To report a vulnerability, open an issue saying you have a security report (no details) and a private channel will be arranged.

## License

MIT — see [LICENSE](./LICENSE).
