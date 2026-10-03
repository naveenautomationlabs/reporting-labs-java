package dev.reportinglabs.core.internal;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the report's own print layout to a {@code report.pdf} next to the HTML, using a headless
 * Chrome / Chromium found on the machine. The report builds its print document when opened with the
 * {@code ?rl-print} flag, so a plain {@code --print-to-pdf} captures the whole thing — executive
 * summary, charts, failure analysis, the test-case table and screenshots of the failures.
 *
 * Best-effort: when no browser is found, or it fails, the HTML report (and its "Export PDF" button)
 * is still there, so nothing about the run breaks. No browser is bundled or downloaded.
 */
final class PdfWriter {
    private PdfWriter() {}

    /** @param htmlPath absolute path to the written index.html. @return the PDF path, or null. */
    static String write(String htmlPath) {
        if (!Config.pdf()) return null;
        try {
            File html = new File(htmlPath);
            File pdf = new File(html.getParentFile(), Config.pdfFile());
            String chrome = findChrome();
            if (chrome == null) {
                System.err.println("[reporting-labs] PDF skipped: no Chrome/Chromium found "
                        + "(set reporting-labs.chromePath, or reporting-labs.pdf=false to silence)");
                return null;
            }
            String url = html.toURI().toString() + "?rl-print=1";
            List<String> cmd = new ArrayList<>();
            cmd.add(chrome);
            cmd.add("--headless");
            cmd.add("--no-sandbox");
            cmd.add("--disable-gpu");
            cmd.add("--no-pdf-header-footer");
            cmd.add("--virtual-time-budget=8000");
            cmd.add("--run-all-compositor-stages-before-draw");
            cmd.add("--print-to-pdf=" + pdf.getAbsolutePath());
            cmd.add(url);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // drain output so the process never blocks on a full pipe
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) { /* discard */ }
            }
            boolean done = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return null; }
            if (pdf.isFile() && pdf.length() > 0) return pdf.getAbsolutePath();
        } catch (Throwable t) {
            System.err.println("[reporting-labs] PDF skipped: " + t.getMessage());
        }
        return null;
    }

    private static String findChrome() {
        String explicit = Config.chromePath();
        if (explicit != null && new File(explicit).isFile()) return explicit;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else if (os.contains("win")) {
            String pf = System.getenv("ProgramFiles");
            String pfx86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LOCALAPPDATA");
            for (String base : new String[]{pf, pfx86, local}) {
                if (base == null) continue;
                candidates.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
                candidates.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
            }
        } else {
            for (String name : new String[]{"google-chrome", "google-chrome-stable", "chromium",
                    "chromium-browser", "microsoft-edge"}) {
                String p = which(name);
                if (p != null) candidates.add(p);
            }
            candidates.add("/usr/bin/google-chrome");
            candidates.add("/usr/bin/chromium");
            candidates.add("/usr/bin/chromium-browser");
            candidates.add("/snap/bin/chromium");
        }
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return c;
        }
        // last resort: a plain name on PATH, for all OSes
        for (String name : new String[]{"google-chrome", "chromium", "chrome"}) {
            String p = which(name);
            if (p != null) return p;
        }
        return null;
    }

    private static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            Path p = Paths.get(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        }
        return null;
    }
}
