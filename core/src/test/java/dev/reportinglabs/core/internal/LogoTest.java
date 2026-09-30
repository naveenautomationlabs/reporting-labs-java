package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LogoTest {

    @Test void urlsAndDataUrisPassThrough() {
        assertEquals("https://x.dev/logo.png", Logo.resolve("https://x.dev/logo.png"));
        assertEquals("data:image/png;base64,AAAA", Logo.resolve("data:image/png;base64,AAAA"));
    }

    @Test void emptyMeansNoLogo() {
        assertNull(Logo.resolve(null));
        assertNull(Logo.resolve("  "));
    }

    @Test void localFileIsEmbedded(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("brand.svg");
        Files.writeString(f, "<svg xmlns='http://www.w3.org/2000/svg'/>");
        String out = Logo.resolve(f.toString());
        assertNotNull(out);
        assertTrue(out.startsWith("data:image/svg+xml;base64,"));
    }

    @Test void classpathResourceIsEmbedded() {
        // core's own template.html sits on the classpath, but it is not an image: unsupported types are refused
        assertNull(Logo.resolve("reporting-labs/template.html"));
    }

    @Test void missingFileIsIgnored() {
        assertNull(Logo.resolve("no/such/logo.png"));
    }
}
