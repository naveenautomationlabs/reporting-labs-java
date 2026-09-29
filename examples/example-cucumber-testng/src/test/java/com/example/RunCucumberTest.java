package com.example;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import org.testng.annotations.DataProvider;

/** The plugin is registered in src/test/resources/cucumber.properties; it
 *  could equally go in @CucumberOptions(plugin = "dev.reportinglabs.cucumber.ReportingLabsPlugin"). */
@CucumberOptions(features = "src/test/resources/features", glue = "com.example")
public class RunCucumberTest extends AbstractTestNGCucumberTests {
    @Override
    @DataProvider(parallel = true)
    public Object[][] scenarios() { return super.scenarios(); }
}
