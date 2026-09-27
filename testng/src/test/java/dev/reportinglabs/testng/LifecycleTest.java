package dev.reportinglabs.testng;

import dev.reportinglabs.core.internal.RlInternal;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Guards the lifecycle contract the Playwright add-on and Selenium base
 * classes rely on: a test slot is already open inside @BeforeMethod, and the
 * just-finished slot is still reachable inside @AfterMethod.
 */
public class LifecycleTest {

    private RlInternal.TestSlot seenInBefore;
    private static RlInternal.TestSlot previous;

    @BeforeMethod
    public void before() {
        seenInBefore = RlInternal.current();
        Assert.assertNotNull(seenInBefore, "slot must be open before @BeforeMethod runs");
        Assert.assertNotSame(seenInBefore, previous, "every invocation gets a fresh slot");
    }

    @Test
    public void slot_opened_in_before_is_the_test_slot() {
        Assert.assertSame(RlInternal.current(), seenInBefore);
    }

    @DataProvider
    public Object[][] rows() { return new Object[][] { { "a" }, { "b" } }; }

    @Test(dataProvider = "rows")
    public void each_data_row_reuses_its_own_before_slot(String row) {
        Assert.assertSame(RlInternal.current(), seenInBefore, "row " + row);
    }

    @AfterMethod(alwaysRun = true)
    public void after() {
        Assert.assertNull(RlInternal.current(), "listener has ended the test before @AfterMethod");
        Assert.assertSame(RlInternal.currentOrLast(), seenInBefore, "after-hook still sees the finished test");
        previous = seenInBefore;
    }
}
