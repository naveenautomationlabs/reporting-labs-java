package dev.reportinglabs.core.internal;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Tees System.out / System.err so every line printed while a test is running
 * lands in that test's "Console output" section — the Java equivalent of the
 * console.log capture the Playwright reporter does. Bytes pass through to the
 * original streams unchanged (Surefire's fork protocol on stdout included);
 * only a copy is read, per thread, line by line.
 */
public final class ConsoleCapture {
    private static volatile boolean installed = false;

    private ConsoleCapture() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            System.setOut(new PrintStream(new Tee(System.out, false), true, "UTF-8"));
            System.setErr(new PrintStream(new Tee(System.err, true),  true, "UTF-8"));
        } catch (Throwable ignore) { /* capture is best-effort */ }
    }

    private static final class Tee extends OutputStream {
        private final PrintStream original;
        private final boolean err;
        private final ThreadLocal<ByteArrayOutputStream> line = ThreadLocal.withInitial(ByteArrayOutputStream::new);

        Tee(PrintStream original, boolean err) { this.original = original; this.err = err; }

        @Override public void write(int b) {
            original.write(b);
            collect(new byte[] { (byte) b }, 0, 1);
        }

        @Override public void write(byte[] b, int off, int len) {
            original.write(b, off, len);
            collect(b, off, len);
        }

        private void collect(byte[] b, int off, int len) {
            ByteArrayOutputStream buf = line.get();
            int start = off, end = off + len;
            for (int i = off; i < end; i++) {
                if (b[i] == '\n') {
                    buf.write(b, start, i - start);
                    emit(buf);
                    start = i + 1;
                }
            }
            if (start < end) buf.write(b, start, end - start);
        }

        private void emit(ByteArrayOutputStream buf) {
            String s = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            buf.reset();
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            try { RlInternal.console(err, s); } catch (Throwable ignore) {}
        }

        @Override public void flush() { original.flush(); }
    }
}
