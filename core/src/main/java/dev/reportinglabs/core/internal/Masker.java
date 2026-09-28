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

    public Masker(Collection<String> extra) {
        for (String k : DEFAULT) keys.add(k);
        if (extra != null) for (String k : extra) keys.add(k.toLowerCase(Locale.ROOT));
        this.substrings = Pattern.compile("(password|secret|token|apikey|authorization|cookie|session|privatekey|clientsecret|accesstoken|refreshtoken)", Pattern.CASE_INSENSITIVE);
        initText(extra);
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
    private static final Set<String> NOT_A_SECRET = new HashSet<>(Arrays.asList(
        "valid", "invalid", "visible", "hidden", "required", "optional", "missing", "empty", "null", "none",
        "correct", "incorrect", "wrong", "right", "expired", "set", "unset", "not", "present", "absent", "ok", "true", "false",
        "masked", "blank", "too", "the", "a", "an", "same", "different", "changed", "unchanged", "shown", "displayed",
        "accepted", "rejected", "weak", "strong"));

    private Pattern keyValue;             // password=x | "password": "x" | token -> x
    private Pattern spoken;               // password is x | with password S3cret@1
    private Pattern mentionsSecret;
    private static final Pattern COMPARED =
        Pattern.compile("\\b(Expected|Received|Actual|expected|received|actual|got|but found|but was)(\\s*[:=]?\\s*)([\"'\\[\\u201c]?)([^\"'\\]\\u201d\\s]+)");

    private static String quote(String s) { return Pattern.quote(s); }

    private void initText(Collection<String> extra) {
        List<String> compound = new ArrayList<>(Arrays.asList("password", "passwd", "pwd", "passcode", "secret", "token",
            "api[_-]?key", "cookie", "credentials?", "private[_-]?key", "access[_-]?key", "session[_-]?id"));
        List<String> spokenKeys = new ArrayList<>(Arrays.asList("password", "passwd", "pwd", "passcode", "secret", "token",
            "api[ _-]?key", "access[ _-]?key", "otp", "pin", "cvv"));
        if (extra != null) for (String k : extra) { compound.add(quote(k)); spokenKeys.add(quote(k)); }
        String key = "(?:[\\w.-]*?(?:" + String.join("|", compound) + ")[\\w-]*|authorization|auth|otp|pin|cvv|ssn)";
        keyValue = Pattern.compile("([\"']?)\\b(" + key + ")\\b([\"']?)(\\s*(?:=>|->|[=:])\\s*)([\"']?)([^\"'\\s&;,}\\]\\)]+)", Pattern.CASE_INSENSITIVE);
        spoken = Pattern.compile("\\b(" + String.join("|", spokenKeys) + ")(s?\\b\\s+(?:is|was|of|as)?\\s*['\"]?)([^\\s'\",;]+)", Pattern.CASE_INSENSITIVE);
        mentionsSecret = Pattern.compile("\\b(?:" + String.join("|", spokenKeys) + ")s?\\b", Pattern.CASE_INSENSITIVE);
    }

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
        for (Pattern p : VALUE_PATTERNS) {
            s = replace(p, s, m -> m.group().matches("(?i)^(Bearer|Basic|Digest|Token)\\s.*") ? m.group().split("\\s+")[0] + " ****" : "****");
        }
        s = replace(keyValue, s, m -> m.group(1) + m.group(2) + m.group(3) + m.group(4) + m.group(5) + "****");
        s = replace(spoken, s, m -> {
            String value = m.group(3), word = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (NOT_A_SECRET.contains(word) || value.equals("****")) return m.group();
            boolean explicit = m.group(2).matches("(?s).*\\b(is|was|of|as)\\b.*") || m.group(2).matches("(?s).*['\"]$");
            return explicit || looksSecret(value) ? m.group(1) + m.group(2) + "****" : m.group();
        });
        if (mentionsSecret.matcher(s).find()) {
            s = replace(COMPARED, s, m -> looksSecret(m.group(4)) ? m.group(1) + m.group(2) + m.group(3) + "****" : m.group());
        }
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
