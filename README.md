# reportingLabs — Java

[![Maven Central](https://img.shields.io/maven-central/v/dev.reportinglabs/reporting-labs-core.svg?label=Maven%20Central)](https://central.sonatype.com/namespace/dev.reportinglabs)
[![Docs](https://img.shields.io/badge/docs-reportinglabs.dev-1A56DB.svg)](https://reportinglabs.dev)

Turn a Java test run into **one HTML file** you can share. No server, no login, no expiry. Open it in a browser, attach it to a Jira ticket, drop it in Slack — it just works.

Ships four Maven artifacts, all under the `dev.reportinglabs` groupId:

- **`reporting-labs-core`** — engine, annotations, `Rl.*` runtime helpers. Framework-agnostic. Pull it in from any test framework.
- **`reporting-labs-junit5`** — JUnit 5 Extension, auto-registered via ServiceLoader.
- **`reporting-labs-testng`** — TestNG listener, auto-registered via ServiceLoader.
- **`reporting-labs-playwright`** — one-line auto-capture for Playwright for Java: request logging, screenshot on failure, trace attachment.

Every port (Node.js, Java, Python later) renders from the same shared HTML template — a Java team's report is byte-for-byte the report a JavaScript team opens.

## Install

**JUnit 5:**

```xml
<dependency>
  <groupId>dev.reportinglabs</groupId>
  <artifactId>reporting-labs-junit5</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

Then turn on JUnit 5 extension auto-detection — create `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

**TestNG:**

```xml
<dependency>
  <groupId>dev.reportinglabs</groupId>
  <artifactId>reporting-labs-testng</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

That's it — the listener auto-registers via ServiceLoader.

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
    // your existing Selenium / Playwright / plain code — no wrappers
  }
}
```

Run `mvn test`. Open `reporting-labs/index.html`.

## Playwright for Java auto-capture

```java
import dev.reportinglabs.playwright.RlPlaywright;
import com.microsoft.playwright.*;

@BeforeEach
void setup() {
  playwright = Playwright.create();
  browser    = playwright.chromium().launch();
  page       = browser.newPage();
  RlPlaywright.attach(page);   // <-- one line, auto-captures everything
}
```

With that line in place:

- Every `page.request()` / `page.goto()` is recorded as an API call in the report,
- A Playwright trace is started and attached on test finish,
- On failure a full-page screenshot is attached automatically.

Nothing else in your test code changes.

## Runtime helpers

For everything you know at runtime, use `Rl` — same surface across every framework binding:

| Method | What it does |
|---|---|
| `Rl.log(msg)` | Step message shown in the test detail's Log panel |
| `Rl.testData(name, obj)` | Pinned data block; sensitive keys masked as `****` |
| `Rl.api(method, url, status)` | Record an HTTP call manually (auto with `RlPlaywright.attach`) |
| `Rl.attach(name, mime, bytes)` | Any binary attachment (screenshot, video, PDF) |
| `Rl.meta(key, value)` | Add a chip to the current test |

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
reporting-labs.outputFolder=target/reporting-labs
reporting-labs.project.name=ShopLite Web
reporting-labs.metadata.env=staging
reporting-labs.links.story=https://shoplite.atlassian.net/browse/{id}
```

Full reference at [reportinglabs.dev](https://reportinglabs.dev/reference/options).

## Works with what you already use

reportingLabs sits on the framework's `@Test` lifecycle — it does not care what happens inside the test body. Same package works for:

- Playwright for Java (auto-capture with `reporting-labs-playwright`)
- Selenium Java: add `reporting-labs-selenium` and your existing `BaseTest` / `DriverFactory` / page objects are found automatically — steps per action, screenshots per policy
- REST Assured (`Rl.api("POST", "/v1/orders", 201)`)
- Karate, Cucumber (both run under JUnit 5 or TestNG)
- Plain code, HttpClient, JDBC, whatever

## Requirements

- JDK 11+
- Maven 3.9+ or Gradle 8+
- JUnit Jupiter 5.10+ or TestNG 7.10+
- Playwright for Java 1.47+ (only for the `reporting-labs-playwright` artifact)

## Contributing

See [PUBLISHING.md](./PUBLISHING.md) for the release runbook.

## License

MIT — see [LICENSE](./LICENSE).
