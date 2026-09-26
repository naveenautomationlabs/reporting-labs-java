package dev.reportinglabs.core.internal;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Redacts values whose keys look sensitive so the report can safely display
 * pinned testData blocks and API headers. Matching is case-insensitive on
 * the whole key (e.g. "authorization") and on common substrings ("secret",
 * "token"). Add project-specific keys with {@link #extra}.
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
    }

    public boolean sensitive(String key) {
        if (key == null) return false;
        String norm = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (keys.contains(norm)) return true;
        return substrings.matcher(key).find();
    }

    /** Walks the value: for a Map, redacts sensitive keys' values; for a
     * List, recurses; otherwise returns the value unchanged. Never mutates
     * the input. */
    public Object apply(Object v) {
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
