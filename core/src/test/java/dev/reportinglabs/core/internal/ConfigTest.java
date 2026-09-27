package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * java.util.Properties has no inline comments, and every reference
 * template shows values like {@code screenshot=always   # never | …}. The
 * option-style keys must tolerate that; free-text keys must not be touched.
 */
class ConfigTest {

    @AfterEach
    void clear() {
        for (String k : new String[] { "screenshot", "trace", "theme", "open", "accent", "customCss", "links.story", "metadata.env" })
            System.clearProperty("reporting-labs." + k);
    }

    @Test
    void inline_comment_is_ignored_on_option_keys() {
        System.setProperty("reporting-labs.screenshot", "always   # never | on-failure | always | only-on-pass");
        System.setProperty("reporting-labs.trace", "retain-on-failure # alias");
        System.setProperty("reporting-labs.theme", "dark\t# auto | light | dark");
        System.setProperty("reporting-labs.open", "on-failure");
        assertEquals("always", Config.screenshot());
        assertEquals("on-failure", Config.trace());
        assertEquals("dark", Config.theme());
        assertEquals("on-failure", Config.open());
        assertTrue(Config.shouldCapture(Config.screenshot(), false), "always must capture a pass");
    }

    @Test
    void free_text_keys_keep_their_hashes() {
        assertEquals("#7C3AED", Config.stripInlineComment("accent", "#7C3AED"));
        assertEquals(".hdr .title{color: #333}", Config.stripInlineComment("customCss", ".hdr .title{color: #333}"));
        assertEquals("https://x.io/browse/{id}#top", Config.stripInlineComment("links.story", "https://x.io/browse/{id}#top"));
        assertEquals("build #42", Config.stripInlineComment("metadata.build", "build #42"));
        assertEquals("Nightly #3", Config.stripInlineComment("title", "Nightly #3"));
    }

    @Test
    void hash_without_leading_whitespace_is_not_a_comment() {
        assertEquals("P0,P1#x", Config.stripInlineComment("dimensionOrder.priority", "P0,P1#x"));
        assertEquals("P0,P1", Config.stripInlineComment("dimensionOrder.priority", "P0,P1 # order"));
    }
}
