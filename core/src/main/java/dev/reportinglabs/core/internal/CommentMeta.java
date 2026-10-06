package dev.reportinglabs.core.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Meta written in the Javadoc of a test method or class, so a team can tag tests without annotations:
 *
 * <pre>
 * /**
 *  * Places an order with a saved card.
 *  * &#64;owner naveen  &#64;priority P0  &#64;story SHOP-12
 *  * &#64;smoke
 *  *&#47;
 * &#64;Test
 * void placesAnOrder() { ... }
 * </pre>
 *
 * {@code @key value} pairs become meta (known keys only, so a stray mention never becomes a chip); a line of
 * bare {@code @words} becomes tags. The class's Javadoc applies to every test in it. Annotations
 * ({@code @Owner}, {@code @Priority}, {@code @Meta}) and {@code Rl.meta()} win over the Javadoc. Javadoc's own
 * tags ({@code @param}, {@code @throws}, {@code @see}...) are ignored. Same syntax as the Node.js and Python reporters.
 */
public final class CommentMeta {

    private CommentMeta() {}

    /** Meta keys a comment may set, besides the configured dimensions and link keys. */
    static final List<String> KEYS = Arrays.asList("priority", "severity", "owner", "feature", "epic", "story", "issue",
        "bug", "component", "module", "team", "sprint", "testcase", "tms", "requirement", "jira", "ticket");

    private static final Set<String> JAVADOC = new HashSet<>(Arrays.asList("param", "return", "returns", "throws",
        "exception", "see", "link", "linkplain", "code", "literal", "since", "version", "author", "deprecated",
        "inheritdoc", "value", "serial", "serialdata", "serialfield", "hidden", "apinote", "implnote", "implspec",
        "index", "summary", "snippet", "systemproperty", "docroot", "provides", "uses", "spec"));

    private static final Pattern PAIR = Pattern.compile(
        "(?:^|\\s)@([A-Za-z][\\w.-]*)(?:[ \\t]*[:=][ \\t]*|[ \\t]+(?!@))?(.*?)(?=\\s+@[A-Za-z][\\w.-]*|$)");

    /** Parsed meta and tags. */
    public static final class Result {
        public final Map<String, String> meta = new LinkedHashMap<>();
        public final List<String> tags = new ArrayList<>();
    }

    /** {@code @key value} pairs and tag lines in a comment's text. */
    public static Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;
        for (String line : text.split("\\r?\\n")) {
            boolean tagLine = line.trim().startsWith("@");
            Matcher m = PAIR.matcher(line);
            while (m.find()) {
                String key = m.group(1);
                if (JAVADOC.contains(key.toLowerCase(Locale.ROOT))) continue;
                String value = m.group(2).trim().replaceAll("^(['\"`])(.*)\\1$", "$2").trim();
                if (!value.isEmpty()) r.meta.put(key.toLowerCase(Locale.ROOT), value);
                else if (tagLine && !r.tags.contains(key)) r.tags.add(key);
            }
        }
        return r;
    }

    /** The Javadoc / block comment right above the declaration at {@code declLine} (1-based), skipping the
     *  annotations in between (also multi-line ones like {@code @CsvSource({ ... })}). Empty when none. */
    public static String above(List<String> lines, int declLine) {
        if (lines == null || declLine < 2 || declLine > lines.size() + 1) return "";
        int i = declLine - 2, depth = 0;
        while (i >= 0) {
            String t = lines.get(i).trim();
            if (depth == 0 && t.endsWith("*/")) break;
            depth += count(t, ')') - count(t, '(');
            if (depth > 0 || t.startsWith("@")) { i--; continue; }   // inside or on an annotation
            if (depth < 0) depth = 0;
            return "";                                               // code or a blank line: no comment
        }
        if (i < 0) return "";
        int end = i;
        while (i >= 0 && !lines.get(i).contains("/*")) i--;
        if (i < 0 || !lines.get(i).trim().startsWith("/*")) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = i; k <= end; k++) {
            String l = lines.get(k);
            if (k == i) l = l.replaceFirst("^\\s*/\\*+", "");
            if (k == end) l = l.replaceFirst("\\*+/\\s*$", "");
            sb.append(l.replaceFirst("^\\s*\\*+ ?", "")).append('\n');
        }
        return sb.toString();
    }

    /** 1-based line of {@code class Name} (or interface / record / enum) in the file, or 0. */
    public static int classLine(List<String> lines, String simpleName) {
        if (lines == null || simpleName == null) return 0;
        Pattern decl = Pattern.compile("\\b(?:class|interface|record|enum)\\s+" + Pattern.quote(simpleName) + "\\b");
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            if (decl.matcher(lines.get(i)).find()) return i + 1;
        }
        return 0;
    }

    private static int count(String s, char c) {
        int n = 0;
        boolean quoted = false;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch == '"' && (k == 0 || s.charAt(k - 1) != '\\')) quoted = !quoted;
            else if (!quoted && ch == c) n++;
        }
        return n;
    }
}
