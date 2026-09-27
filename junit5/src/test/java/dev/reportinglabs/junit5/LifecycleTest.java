package dev.reportinglabs.junit5;

import dev.reportinglabs.core.internal.RlInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the lifecycle contract the Playwright add-on and Selenium base
 * classes rely on: a test slot is already open inside @BeforeEach, and the
 * just-finished slot is still reachable inside @AfterEach.
 */
class LifecycleTest {

    private RlInternal.TestSlot seenInBefore;
    private static RlInternal.TestSlot previous;

    @BeforeEach
    void before() {
        seenInBefore = RlInternal.current();
        assertNotNull(seenInBefore, "slot must be open before @BeforeEach runs");
        assertNotSame(seenInBefore, previous, "every invocation gets a fresh slot");
    }

    @Test
    void slot_opened_in_before_is_the_test_slot() {
        assertSame(RlInternal.current(), seenInBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = { "a", "b" })
    void each_invocation_reuses_its_own_before_slot(String row) {
        assertSame(RlInternal.current(), seenInBefore, "row " + row);
    }

    @AfterEach
    void after() {
        assertNull(RlInternal.current(), "extension has ended the test before @AfterEach");
        assertSame(RlInternal.currentOrLast(), seenInBefore, "after-hook still sees the finished test");
        previous = seenInBefore;
    }
}
