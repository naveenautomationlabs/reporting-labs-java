package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommentMetaTest {

    @Test void pairsAndTagLines() {
        CommentMeta.Result r = CommentMeta.parse("Places an order.\n@owner naveen  @priority P0  @story SHOP-12\n@smoke @regression");
        assertEquals(Map.of("owner", "naveen", "priority", "P0", "story", "SHOP-12"), r.meta);
        assertEquals(Arrays.asList("smoke", "regression"), r.tags);
    }

    @Test void quotesMentionsAndJavadocTags() {
        CommentMeta.Result r = CommentMeta.parse("@owner 'naveen' @feature \"Cart and checkout\"\nReported by @asha, see {@link Foo}\n@param x the value\n@author someone");
        assertEquals("naveen", r.meta.get("owner"));
        assertEquals("Cart and checkout", r.meta.get("feature"));
        assertFalse(r.meta.containsKey("param") || r.meta.containsKey("author") || r.meta.containsKey("link"));
        assertTrue(r.tags.isEmpty(), "a mention in a sentence is not a tag");   // `asha` is an unknown key: dropped by applyComments
    }

    @Test void commentAboveAnnotationsIncludingMultiLine() {
        List<String> src = Arrays.asList(
            "class A {",                                   // 1
            "    /**",                                     // 2
            "     * @owner asha @priority P1",             // 3
            "     */",                                     // 4
            "    @ParameterizedTest",                      // 5
            "    @CsvSource({",                            // 6
            "        \"a (x), 1\",",                       // 7
            "        \"b, 2\"",                            // 8
            "    })",                                      // 9
            "    void m(String s, int n) {}",              // 10
            "",                                            // 11
            "    /** @owner nope */ int x;",               // 12
            "    @Test",                                   // 13
            "    void n() {}",                             // 14
            "",                                            // 15
            "    /** @owner ravi */",                      // 16
            "",                                            // 17
            "    @Test void o() {}",                       // 18
            "}");
        assertEquals(Map.of("owner", "asha", "priority", "P1"), CommentMeta.parse(CommentMeta.above(src, 10)).meta);
        assertEquals("", CommentMeta.above(src, 14).trim(), "a comment that ends a code line is not the test's");
        assertEquals("", CommentMeta.above(src, 18).trim(), "a blank line separates the comment from the test");
        assertEquals(1, CommentMeta.classLine(src, "A"));
    }
}
