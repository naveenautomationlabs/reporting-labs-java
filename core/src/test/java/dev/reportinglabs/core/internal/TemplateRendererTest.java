package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplateRendererTest {
    @Test void themeAndPaletteLandOnTheHtmlTag() {
        String html = "<!doctype html>\n<html lang=\"en\" data-palette=\"lab\">\n<head></head></html>";
        assertEquals("<!doctype html>\n<html lang=\"en\" data-theme=\"dark\" data-palette=\"ocean\">\n<head></head></html>", TemplateRenderer.applyThemeAttributes(html, "dark", "ocean"));
        assertEquals("<!doctype html>\n<html lang=\"en\" data-palette=\"ember\">\n<head></head></html>", TemplateRenderer.applyThemeAttributes(html, "auto", "ember"));
        assertEquals("<!doctype html>\n<html lang=\"en\" data-palette=\"lab\">\n<head></head></html>", TemplateRenderer.applyThemeAttributes(html, null, "lab"));
    }

    @Test void embeddedFontsCanBeDropped() {
        String html = "<head><style>@font-face{font-family:'IBM Plex Sans';src:url(data:font/woff2;base64,AAAA)}</style><style>body{}</style></head>";
        String out = TemplateRenderer.dropEmbeddedFonts(html);
        assertFalse(out.contains("@font-face"));
        assertTrue(out.contains("fonts.googleapis.com"));
        assertTrue(out.contains("<style>body{}</style>"));
    }
}
