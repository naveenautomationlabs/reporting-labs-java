package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HTML template indexes a few option fields unconditionally. If any of
 * them is missing the report renders blank (the failure only shows once a
 * comparator actually runs — i.e. with two or more failed tests). These
 * tests pin the shape the template needs.
 */
class ReportBuilderTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildWithTwoFailures() {
        List<RlInternal.TestSlot> slots = new ArrayList<>();
        for (String name : new String[] { "a", "b" }) {
            RlInternal.TestSlot s = RlInternal.begin(name, "T.java", 0, "junit5", List.of("T"));
            RlInternal.meta("priority", "P0");
            RlInternal.end(new AssertionError("boom " + name), false);
            slots.add(s);
        }
        return ReportBuilder.build(slots, System.currentTimeMillis() - 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dimensionOrder_always_has_priority_and_severity() {
        Map<String, Object> data = buildWithTwoFailures();
        Map<String, Object> options = (Map<String, Object>) data.get("options");
        Map<String, List<String>> order = (Map<String, List<String>>) options.get("dimensionOrder");
        assertEquals(List.of("P0", "P1", "P2", "P3", "P4"), order.get("priority"));
        assertTrue(order.get("severity").contains("blocker"));
        assertTrue(order.get("severity").contains("trivial"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void options_carry_every_field_the_template_reads() {
        Map<String, Object> options = (Map<String, Object>) buildWithTwoFailures().get("options");
        for (String key : new String[] { "widgets", "dimensions", "dimensionOrder", "links", "sections" }) {
            assertNotNull(options.get(key), "options." + key);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failed_tests_carry_error_message_and_stack() {
        Map<String, Object> data = buildWithTwoFailures();
        List<Map<String, Object>> tests = (List<Map<String, Object>>) data.get("tests");
        assertEquals(2, tests.size());
        for (Map<String, Object> t : tests) {
            assertEquals("failed", t.get("outcome"));
            List<Map<String, Object>> results = (List<Map<String, Object>>) t.get("results");
            List<Map<String, Object>> errors = (List<Map<String, Object>>) results.get(0).get("errors");
            assertEquals(1, errors.size());
            assertTrue(String.valueOf(errors.get(0).get("message")).startsWith("boom"));
            assertNotNull(t.get("annotations"));
        }
    }
}
