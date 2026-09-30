package dev.reportinglabs.core.internal;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Redacts values whose keys look sensitive so the report can safely display
 * pinned testData blocks and API headers. Matching is case-insensitive on
 * the whole key (e.g. "authorization") and on common substrings ("secret",
 * "token"). Free text (log lines, console output, errors, step titles)
 * goes through {@link #maskText}. Add project-specific keys via
 * reporting-labs.maskKeys.
 */
public final class Masker {
    private static final String[] DEFAULT = {
        "password", "passwd", "pwd", "secret", "token", "apikey", "api_key",
        "authorization", "auth", "cookie", "session", "csrf", "xsrf",
        "privatekey", "private_key", "clientsecret", "client_secret",
        "accesstoken", "access_token", "refreshtoken", "refresh_token",
        "cardnumber", "card_number", "card", "cvv", "cvc", "pan", "ssn",
    };

    private final Set<String> keys = new HashSet<>();
    private final Pattern substrings;

    public Masker() { this(Collections.emptyList()); }

    public Masker(Collection<String> extra) { this(extra, Collections.emptyList(), false); }

    /**
     * @param extra       extra sensitive key substrings (reporting-labs.maskKeys)
     * @param knownValues literal values to blank wherever they appear (reporting-labs.maskValues)
     * @param fromEnv     also learn the values of environment variables and system
     *                    properties whose names look sensitive (PASSWORD, API_TOKEN, …)
     */
    public Masker(Collection<String> extra, Collection<String> knownValues, boolean fromEnv) {
        for (String k : DEFAULT) keys.add(k);
        if (extra != null) for (String k : extra) keys.add(k.toLowerCase(Locale.ROOT));
        this.substrings = Pattern.compile("(password|secret|token|apikey|authorization|cookie|session|privatekey|clientsecret|accesstoken|refreshtoken)", Pattern.CASE_INSENSITIVE);
        initText(extra);
        if (knownValues != null) for (String v : knownValues) learn(v, 4);
        if (fromEnv) learnFromEnvironment();
    }

    // ---- values already known to be secrets ----

    /** Every value the key patterns have masked, the values under sensitive
     *  keys in data blocks, reporting-labs.maskValues, and sensitive-looking
     *  environment variables. Once known, a value is blanked wherever it
     *  shows up later, keyed or not: "Logging in as admin / s3cret" is
     *  masked because "password=s3cret" (or a PASSWORD env var) came first. */
    private final Set<String> learned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Auth schemes and placeholders that follow a sensitive key but are not the secret. */
    private static final Set<String> NOT_A_VALUE = new HashSet<>(Arrays.asList("bearer", "basic", "digest", "token", "undefined", "redacted", "hidden", "secret", "password"));
    private volatile Pattern learnedPattern;
    private volatile boolean learnedDirty;
    private static final int MAX_LEARNED = 500;

    void learn(String value) { learn(value, 4); }

    private void learn(String value, int minLength) {
        if (value == null) return;
        String v = value.trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) v = v.substring(1, v.length() - 1);
        if (v.length() < minLength || v.contains("***") || !v.matches(".*[A-Za-z0-9].*") || learned.size() >= MAX_LEARNED) return;
        String word = v.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (NOT_A_SECRET.contains(word) || NOT_A_VALUE.contains(word)) return;
        if (learned.add(v)) learnedDirty = true;
    }

    /** PASSWORD=…, API_TOKEN=…, OAUTH_CLIENT_SECRET=… in the environment or
     *  -D properties: the values a CI job injects are the ones that end up
     *  in log lines. Short values are skipped so a plain word is not blanked
     *  across the whole report. */
    private void learnFromEnvironment() {
        try { for (Map.Entry<String, String> e : System.getenv().entrySet()) if (sensitive(e.getKey())) learn(e.getValue(), 6); } catch (Throwable ignore) {}
        try { for (String k : System.getProperties().stringPropertyNames()) if (sensitive(k)) learn(System.getProperty(k), 6); } catch (Throwable ignore) {}
    }

    private Pattern learnedPattern() {
        if (!learnedDirty) return learnedPattern;
        synchronized (learned) {
            if (!learnedDirty) return learnedPattern;
            List<String> vals = new ArrayList<>(learned);
            vals.sort((a, b) -> b.length() - a.length());   // longest first so "s3cret!" wins over "s3cret"
            StringBuilder sb = new StringBuilder();
            for (String v : vals) {
                if (sb.length() > 0) sb.append('|');
                boolean word = v.matches("[A-Za-z0-9_]+");
                if (word) sb.append("(?<![A-Za-z0-9_])");
                sb.append(Pattern.quote(v));
                if (word) sb.append("(?![A-Za-z0-9_])");
            }
            learnedPattern = sb.length() == 0 ? null : Pattern.compile(sb.toString());
            learnedDirty = false;
            return learnedPattern;
        }
    }

    public boolean sensitive(String key) {
        if (key == null) return false;
        String norm = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (keys.contains(norm)) return true;
        // Match on the normalised form too, so "x-api-key" / "X_Auth_Token"
        // hit "apikey" / "token" the same way "apiKey" does.
        return substrings.matcher(norm).find();
    }

    // ---- free text (log lines, console output, error messages, step titles) ----

    /** Values that are secrets on their own, whatever the surrounding text. */
    private static final Pattern[] VALUE_PATTERNS = {
        Pattern.compile("\\b(Bearer|Basic|Digest|Token)\\s+[A-Za-z0-9._~+/=-]{8,}", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}"),          // JWT
        Pattern.compile("\\b(?:sk|pk|rk)[-_](?:live|test)?[-_]?[A-Za-z0-9]{12,}\\b"),                  // Stripe-style
        Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}\\b"),                            // GitHub
        Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
        Pattern.compile("\\bxox[abpr]-[A-Za-z0-9-]{10,}\\b"),                                          // Slack
        Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),                                                     // AWS
        Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}\\b"),                                               // Google API key
        Pattern.compile("\\bya29\\.[0-9A-Za-z_-]{20,}\\b"),                                            // Google OAuth
        Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"),                                             // GitLab
        Pattern.compile("\\bnpm_[A-Za-z0-9]{30,}\\b"),
        Pattern.compile("\\bSG\\.[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{16,}\\b"),                         // SendGrid
    };
    /** Words after "password is ..." that are plainly not a secret. */
    /** 13 to 19 digits, spaces or dashes between groups allowed; masked only when the Luhn check passes. */
    private static final Pattern CARD_NUMBER = Pattern.compile("(?<![\\w.-])\\d(?:[ -]?\\d){12,18}(?![\\w.-])");

    private static boolean luhn(String s) {
        int sum = 0; boolean dbl = false; int digits = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') continue;
            int d = c - '0'; digits++;
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d; dbl = !dbl;
        }
        return digits >= 13 && sum % 10 == 0;
    }

    private static final Set<String> NOT_A_SECRET = new HashSet<>(Arrays.asList(
        "valid", "invalid", "visible", "hidden", "required", "optional", "missing", "empty", "null", "none",
        "correct", "incorrect", "wrong", "right", "expired", "set", "unset", "not", "present", "absent", "ok", "true", "false",
        "masked", "blank", "too", "the", "a", "an", "same", "different", "changed", "unchanged", "shown", "displayed",
        "accepted", "rejected", "weak", "strong"));

    private Pattern keyValue;             // password=x | "password": "x" | token -> x
    private Pattern spoken;               // password is x | with password S3cret@1
    private Pattern mentionsSecret;
    private Pattern quotedPair;       // "password", "x"
    private static final Pattern COMPARED =
        Pattern.compile("\\b(Expected|Received|Actual|expected|received|actual|got|but found|but was)(\\s*[:=]?\\s*)([\"'\\[\\u201c]?)([^\"'\\]\\u201d\\s]+)");

    private static String quote(String s) { return Pattern.quote(s); }

    private void initText(Collection<String> extra) {
        List<String> compound = new ArrayList<>(Arrays.asList("password", "passwort", "passwd", "pwd", "passcode", "secret", "token",
            "api[ _-]?key", "cookie", "credentials?", "private[ _-]?key", "access[ _-]?key", "session[ _-]?id", "(?<![A-Za-z])auth(?![A-Za-z])"));
        List<String> spokenKeys = new ArrayList<>(Arrays.asList("password", "passwort", "passwd", "pwd", "pass", "passcode", "secret", "token",
            "api[ _-]?key", "access[ _-]?key", "otp", "pin", "cvv"));
        if (extra != null) for (String k : extra) { compound.add(quote(k)); spokenKeys.add(quote(k)); }
        // A key is a word carrying one of the compound parts anywhere (db.password, x-api-key, user.password.value,
        // accessToken) or one of the exact short names.
        String key = "(?:[\\w.-]*?(?:" + String.join("|", compound) + ")[\\w.-]*|authorization|pass|pw|otp|pin|cvv|cvc|ssn|pan|iban|card|card[_-]?(?:number|no|num)|cardnumber)";
        keyValue = Pattern.compile("([\"']?)\\b(" + key + ")\\b([\"']?)(\\s*(?:=>|->|[=:])\\s*)([\"']?)([^\"'\\s&;,}\\]\\)]+)", Pattern.CASE_INSENSITIVE);
        // Code and map literals: ("#password", "x") / Map.of("password", "x") — a quoted key, a comma, a quoted value.
        quotedPair = Pattern.compile("([\"'])#?(" + key + ")\\1(\\s*,\\s*)([\"'])([^\"']+)\\4", Pattern.CASE_INSENSITIVE);
        // "password is x", "password for admin is x", "token: x", "the token is: x", "with pwd x"
        spoken = Pattern.compile("\\b(" + String.join("|", spokenKeys) + ")"
            + "(s?\\b(?:\\s+for\\s+(?:\\S+\\s+){1,3}?(?:[:=]|(?:is|was)\\b)\\s*|\\s*[:=]?\\s*(?:(?:is|was|of|as)\\b\\s*)?[:=]?\\s*)['\"]?)"
            + "((?=[A-Za-z0-9])[^\\s'\",;]+)", Pattern.CASE_INSENSITIVE);
        mentionsSecret = Pattern.compile("\\b(?:" + String.join("|", spokenKeys) + ")s?\\b", Pattern.CASE_INSENSITIVE);
    }

    /** user:password@host in a URL, curl -u user:password, "credentials admin:x". */
    private static final Pattern URL_USERINFO = Pattern.compile("(://[^\\s/:@]+:)([^\\s@]+)(@)");
    private static final Pattern CLI_USER = Pattern.compile("((?:^|\\s)(?:-u|--user|--username|--credentials?)\\s+[^\\s:]+:)(\\S+)");
    private static final Pattern CREDS_PAIR = Pattern.compile("\\b(credentials?|creds|login)(\\s*[:=]?\\s+[^\\s:'\"]+:)([^\\s'\",;]+)", Pattern.CASE_INSENSITIVE);
    /** The value comes first: "Typed s3cret into password field", "Entered x in #password". */
    private static final Pattern VALUE_THEN_KEY = Pattern.compile("([^\\s'\"(]+)(\\s+(?:into|in|to|for|as)\\s+(?:the\\s+)?['\"#]?[\\w-]*?(?:password|passwd|pwd|secret|token|otp|pin)\\b)", Pattern.CASE_INSENSITIVE);

    private static boolean looksSecret(String v) {
        if (v.matches(".*\\s.*")) return false;
        boolean digit = v.matches(".*\\d.*"), special = v.matches(".*[^A-Za-z0-9].*"), mixed = v.matches(".*([a-z][A-Z]|[A-Z][a-z].*[A-Z]).*");
        return digit && v.length() >= 4 || special && v.length() >= 6 || mixed && v.length() >= 8;
    }

    private static String replace(Pattern p, String s, java.util.function.Function<java.util.regex.Matcher, String> fn) {
        java.util.regex.Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fn.apply(m)));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Masks secrets inside free text: key=value pairs in any quoting style,
     *  "password is …", Bearer/Basic credentials, JWTs, well-known token
     *  formats, and compared values in assertion messages that mention a
     *  secret. Null-safe. */
    public String maskText(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text;
        s = replace(CARD_NUMBER, s, m -> luhn(m.group()) ? "****" : m.group());
        for (Pattern p : VALUE_PATTERNS) {
            s = replace(p, s, m -> {
                if (m.group().matches("(?i)^(Bearer|Basic|Digest|Token)\\s.*")) { String[] parts = m.group().split("\\s+", 2); learn(parts[1]); return parts[0] + " ****"; }
                learn(m.group()); return "****";
            });
        }
        s = replace(URL_USERINFO, s, m -> { learn(m.group(2)); return m.group(1) + "****" + m.group(3); });
        s = replace(CLI_USER, s, m -> { learn(m.group(2)); return m.group(1) + "****"; });
        s = replace(CREDS_PAIR, s, m -> { learn(m.group(3)); return m.group(1) + m.group(2) + "****"; });
        s = replace(keyValue, s, m -> { learn(m.group(6)); return m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5) + "****"; });
        s = replace(quotedPair, s, m -> { learn(m.group(5)); return m.group().substring(0, m.group().length() - m.group(5).length() - 1) + "****" + m.group(4); });
        s = replace(spoken, s, m -> {
            String value = m.group(3), word = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (NOT_A_SECRET.contains(word) || value.equals("****") || value.matches("[:=.,!?]+")) return m.group();
            boolean explicit = m.group(2).matches("(?s).*\\b(is|was|of|as)\\b.*") || m.group(2).matches("(?s).*['\"]$") || m.group(2).matches("(?s).*[:=]\\s*$");
            if (explicit || looksSecret(value)) { learn(value); return m.group(1) + m.group(2) + "****"; }
            return m.group();
        });
        s = replace(VALUE_THEN_KEY, s, m -> {
            String value = m.group(1), word = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (NOT_A_SECRET.contains(word) || value.contains("*") || !looksSecret(value)) return m.group();
            learn(value); return "****" + m.group(2);
        });
        if (mentionsSecret.matcher(s).find()) {
            s = replace(COMPARED, s, m -> { if (!looksSecret(m.group(4))) return m.group(); learn(m.group(4)); return m.group(1) + m.group(2) + m.group(3) + "****"; });
        }
        Pattern known = learnedPattern();
        if (known != null) s = known.matcher(s).replaceAll("****");
        return s;
    }

    /** Walks the value: for a Map, redacts sensitive keys' values; for a
     * List, recurses; strings go through {@link #maskText}; other values
     * are returned unchanged. Never mutates the input. */
    public Object apply(Object v) {
        if (v instanceof String) return maskText((String) v);
        if (v instanceof Map) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                String k = String.valueOf(e.getKey());
                if (sensitive(k) && e.getValue() instanceof String) learn((String) e.getValue());
                out.put(k, sensitive(k) ? "****" : apply(e.getValue()));
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> src = (List<Object>) v;
            List<Object> out = new ArrayList<>(src.size());
            for (Object e : src) out.add(apply(e));
            return out;
        }
        return v;
    }
}
