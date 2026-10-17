package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The re-run command: a test method by class#method, a Cucumber scenario by feature file and line. */
class RerunTest {

    static class Nested { }

    @SuppressWarnings("unchecked")
    @Test
    void testMethodsRunByClassAndMethod() {
        RlInternal.TestSlot s = RlInternal.begin("locks account", RerunTest.class, "testMethodsRunByClassAndMethod", "junit5", List.of("RerunTest"));
        RlInternal.end(new AssertionError("boom"), false);
        RlInternal.TestSlot n = RlInternal.begin("nested", Nested.class, "go", "junit5", List.of("Nested"));
        RlInternal.end(null, false);
        Map<String, Object> data = ReportBuilder.build(List.of(s, n), System.currentTimeMillis() - 10);
        Map<String, Object> shape = (Map<String, Object>) data.get("rerun");
        assertEquals("mvn test -Dtest=\"", shape.get("pre"));
        assertEquals(",", shape.get("sep"));
        assertEquals("\"", shape.get("post"));
        List<Map<String, Object>> tests = (List<Map<String, Object>>) data.get("tests");
        assertEquals("dev.reportinglabs.core.internal.RerunTest#testMethodsRunByClassAndMethod", ((List<String>) tests.get(0).get("rerun")).get(0));
        // a nested class keeps its $, escaped for the shell inside the double quotes
        assertEquals("dev.reportinglabs.core.internal.RerunTest\\$Nested#go", ((List<String>) tests.get(1).get("rerun")).get(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    void scenariosRunByFeatureFileAndLine() {
        RlInternal.TestSlot s = RlInternal.begin("Add one item", "src/test/resources/features/cart.feature", 8, "cucumber", List.of("Cart"));
        RlInternal.end(null, false);
        Map<String, Object> data = ReportBuilder.build(List.of(s), System.currentTimeMillis() - 10);
        assertEquals("mvn test -Dcucumber.features=\"", ((Map<String, Object>) data.get("rerun")).get("pre"));
        List<Map<String, Object>> tests = (List<Map<String, Object>>) data.get("tests");
        assertEquals(List.of("src/test/resources/features/cart.feature:8"), tests.get(0).get("rerun"));
    }

    @Test
    void noCommandWithoutASource() {
        RlInternal.TestSlot s = RlInternal.begin("dynamic", "T.java", 0, "junit5", List.of("T"));
        RlInternal.end(null, false);
        assertNull(ReportBuilder.build(List.of(s), System.currentTimeMillis() - 10).get("rerun"));
    }
}
