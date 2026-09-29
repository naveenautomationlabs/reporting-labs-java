# reportingLabs — Java

[![Maven Central](https://img.shields.io/maven-central/v/dev.reportinglabs/reporting-labs-core.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/dev.reportinglabs)
[![Docs](https://img.shields.io/badge/docs-reportinglabs.dev-1A56DB.svg)](https://reportinglabs.dev)

Turn a Java test run into **one HTML file** you can share. No server, no login, no expiry. Open it in a browser, attach it to a Jira ticket, drop it in Slack — it just works.

Seven Maven artifacts, all under the `dev.reportinglabs` groupId. Pick the one for your test framework, then add the one for your tool:

| Artifact | What it is |
|---|---|
| `reporting-labs-testng` | TestNG listener, auto-registered via ServiceLoader |
| `reporting-labs-junit5` | JUnit 5 extension, auto-registered via ServiceLoader |
| `reporting-labs-selenium` | Zero code: finds the WebDriver on your test instance, records every open/click/type as a step, screenshots per policy |
| `reporting-labs-rest-assured` | Zero code: registers a recording filter, every request lands in the API tab with headers, bodies, status and timing |
| `reporting-labs-playwright` | Zero code: finds the Page, BrowserContext, Browser or APIRequestContext on your test instance; API calls, trace, screenshot and video per policy |
| `reporting-labs-cucumber` | Cucumber JVM plugin: one row per scenario, named after it, at its feature-file line, with Given/When/Then as steps. TestNG runner or JUnit Platform engine |
| `reporting-labs-core` | Engine, annotations, `Rl.*` helpers. Comes with the two above; use it alone from plain code |

Every port (Node.js, Java) renders the same HTML template. A Java team's report is byte-for-byte the report a JavaScript team opens.

## Install

Step by step, with screenshots, per tool: [Selenium](https://reportinglabs.dev/get-started/java/selenium) · [Playwright](https://reportinglabs.dev/get-started/java/playwright) · [REST Assured](https://reportinglabs.dev/get-started/java/rest-assured) · [Cucumber](https://reportinglabs.dev/get-started/java/cucumber) · [Other tools](https://reportinglabs.dev/get-started/java/other-tools).

**TestNG:**

```xml
<dependency>
  <groupId>dev.reportinglabs</groupId>
  <artifactId>reporting-labs-testng</artifactId>
  <version>0.1.14</version>
  <scope>test</scope>
</dependency>
```

Nothing to register: the listener is found through ServiceLoader. If you keep a `<listeners>` block in `testng.xml`, `dev.reportinglabs.testng.ReportingLabsListener` can go there too.

**JUnit 5:**

```xml
<dependency>
  <groupId>dev.reportinglabs</groupId>
  <artifactId>reporting-labs-junit5</artifactId>
  <version>0.1.14</version>
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
- Before/After hooks with timings, `Rl.step()` groups, Selenium actions, `System.out` / `System.err` lines, retries grouped as attempts and marked flaky, DataProvider rows as Parameters.
- API calls with headers, bodies and Copy as cURL. Screenshots, traces and videos per policy.
- Secrets masked everywhere: headers, bodies, log lines, console output, data blocks, error messages, even `"password", "x"` literals in a code snippet.
- Trend, new vs known failures, flaky history and got-slower across runs, from `reporting-labs.history.json`.
- One lane per worker thread on the Timeline.

## Selenium: zero code

Add `reporting-labs-selenium`. Your `BaseTest`, `DriverFactory` and page objects stay as they are: the WebDriver is found on the test instance (a field, a base class, a `ThreadLocal`, a page object) and wrapped with a step recorder. `RlSelenium.attach(driver)` remains for a driver kept out of sight, `RlSelenium.screenshot("name.png")` for an extra screenshot mid-test.

## REST Assured: zero code

Add `reporting-labs-rest-assured`. The recording filter goes into `RestAssured.filters()` when the run starts (again after a `RestAssured.reset()`). Query and path params resolved, form fields and multipart part names, text bodies up to 200 KB, binary types as a placeholder, failed requests with status 0. `reporting-labs.restassured.autoRecord=false` turns it off.

## Cucumber JVM: one property

Add `reporting-labs-cucumber` and register the plugin once:

```properties
# src/test/resources/cucumber.properties (TestNG runner, JUnit 4 runner, CLI)
# src/test/resources/junit-platform.properties (JUnit Platform engine)
cucumber.plugin=dev.reportinglabs.cucumber.ReportingLabsPlugin
```

Every scenario is one row named after the scenario, at `orders.feature:13`, with the Gherkin steps (Background included) as steps, `@Before`/`@After` hooks in the hook groups, data tables and doc strings as data blocks, Scenario Outline rows titled with their example values. Tags become filters: `@P1` is the priority, `@blocker` the severity, `@owner:naveen` an owner chip, everything else a tag. An undefined step points at the feature line, and the steps after a failure show as "not run". With the TestNG runner add `reporting-labs-testng` as usual; with the JUnit Platform engine the plugin alone is enough. Selenium and REST Assured add-ons work inside step definitions unchanged.

## Playwright for Java: zero code

Add `reporting-labs-playwright`. Your `BaseTest`, `PlaywrightFactory` and page objects stay as they are: the `Page` (or `BrowserContext`, `Browser`, `APIRequestContext`) is found on the test instance, in a base class, a page object, a factory or a `ThreadLocal`, static holders included, and wired for API capture, trace and screenshot per policy. An `APIRequestContext` field is swapped for a recording wrapper, so API-only tests get the API tab too.

Two things still take a line, because the object never sits on the test:

```java
context = browser.newContext(RlPlaywright.contextOptions());   // video: Playwright decides at context creation
APIRequestContext api = RlPlaywright.record(page.request());   // page.request() inside a test body
```

`RlPlaywright.attach(page)` / `attach(context)` remain for a page kept somewhere the discovery cannot see. `reporting-labs.playwright.autoAttach=false` turns the discovery off.

## A first test

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
reporting-labs.project.name=ShopLite Web
reporting-labs.metadata.env=staging
reporting-labs.links.story=https://shoplite.atlassian.net/browse/{id}
reporting-labs.maskKeys=otp,pan

# Selenium: never | on-failure | always | only-on-pass
reporting-labs.selenium.screenshot=on-failure

# Playwright
reporting-labs.playwright.screenshot=on-failure
reporting-labs.playwright.trace=on-failure
reporting-labs.playwright.video=never

# REST Assured
reporting-labs.restassured.autoRecord=true
```

Comments go on their own line; `java.util.Properties` has no inline comments.

Full reference at [reportinglabs.dev](https://reportinglabs.dev/reference/options).

## Works with what you already use

reportingLabs sits on the framework's `@Test` lifecycle — it does not care what happens inside the test body. Same package works for:

- Selenium and Appium (`reporting-labs-selenium`)
- REST Assured (`reporting-labs-rest-assured`)
- Playwright for Java (`reporting-labs-playwright`)
- Cucumber JVM (`reporting-labs-cucumber`), on the TestNG runner or the JUnit Platform engine; Karate is on the roadmap
- Plain code, HttpClient, JDBC: `Rl.api()` and `Rl.testData()` by hand

## Requirements

- JDK 11+
- Maven 3.9+ or Gradle 8+
- JUnit Jupiter 5.10+ or TestNG 7.5+
- Selenium 4.x, REST Assured 4.x to 6.x, Playwright for Java 1.47+ (each only for its add-on)

## Contributing

See [PUBLISHING.md](./PUBLISHING.md) for the release runbook.

## License

MIT — see [LICENSE](./LICENSE).
