package dev.reportinglabs.restassured;

import dev.reportinglabs.core.Rl;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.restassured.specification.MultiPartSpecification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * REST Assured filter that records every request and response into the
 * current test: method, URL (query and path params resolved), status,
 * timing, request and response headers, request body (JSON, form fields,
 * multipart part names) and the response body for text types. Secrets are
 * masked by the core before anything reaches the report.
 *
 * With reporting-labs-rest-assured on the classpath this filter is added to
 * {@code RestAssured.filters()} automatically; you only need it yourself for
 * a per-request {@code given().filter(new RlRestAssuredFilter())} or when
 * reporting-labs.restassured.autoRecord=false.
 */
public final class RlRestAssuredFilter implements Filter {

    /** Bodies beyond this are truncated (same cap as the Node reporter and the Playwright add-on). */
    private static final int MAX_BODY = 200 * 1024;
    private static final Pattern TEXT_TYPES = Pattern.compile("json|text|xml|html|javascript|x-www-form-urlencoded|graphql", Pattern.CASE_INSENSITIVE);
    private static final String MARK = "reporting-labs.recorded";

    @Override
    public Response filter(FilterableRequestSpecification req, FilterableResponseSpecification res, FilterContext ctx) {
        // The filter may sit in the global list and on the request: record once.
        if (Boolean.TRUE.equals(ctx.getValue(MARK))) return ctx.next(req, res);
        ctx.setValue(MARK, Boolean.TRUE);

        long started = System.currentTimeMillis();
        Response response;
        try {
            response = ctx.next(req, res);
        } catch (Throwable e) {
            // A refused connection surfaces as a checked java.net exception: record it, then rethrow as-is.
            record(req, null, System.currentTimeMillis() - started, "Request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw RlRestAssuredFilter.<RuntimeException>sneaky(e);
        }
        record(req, response, System.currentTimeMillis() - started, null);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T { throw (T) t; }

    private static void record(FilterableRequestSpecification req, Response response, long duration, String failure) {
        try {
            String url;
            try { url = req.getURI(); } catch (Throwable t) { url = String.valueOf(req.getBaseUri()) + req.getBasePath(); }
            String respBody = failure;
            Map<String, String> respHeaders = Map.of();
            int status = 0;
            if (response != null) {
                status = response.getStatusCode();
                respHeaders = headers(response.getHeaders());
                respBody = textBody(response.getContentType(), response.getHeader("Content-Length"), response);
            }
            Rl.api(req.getMethod(), url, status, duration, headers(req.getHeaders()), requestBody(req), respHeaders, respBody);
        } catch (Throwable ignore) { /* recording never fails the test */ }
    }

    private static String requestBody(FilterableRequestSpecification req) {
        Object body = req.getBody();
        if (body != null) return cap(body instanceof byte[] ? "<binary " + ((byte[]) body).length + " bytes>" : String.valueOf(body));
        Map<String, String> form = req.getFormParams();
        if (form != null && !form.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : form.entrySet()) sb.append(sb.length() == 0 ? "" : "&").append(e.getKey()).append('=').append(e.getValue());
            return sb.toString();
        }
        List<MultiPartSpecification> parts = req.getMultiPartParams();
        if (parts != null && !parts.isEmpty()) {
            StringBuilder sb = new StringBuilder("multipart: ");
            for (MultiPartSpecification p : parts) {
                Object content = p.getContent();
                if (content instanceof CharSequence) sb.append(p.getControlName()).append('=').append(content);
                else sb.append(p.getControlName()).append(" (file ").append(p.getFileName() != null ? p.getFileName() : "").append(content instanceof byte[] ? ", " + ((byte[]) content).length + " bytes" : "").append(')');
                sb.append("; ");
            }
            return sb.toString().trim();
        }
        return null;
    }

    private static String textBody(String contentType, String length, Response response) {
        String type = contentType == null ? "" : contentType;
        if (!type.isEmpty() && !TEXT_TYPES.matcher(type).find()) {
            return "<" + type.split(";")[0] + (length != null ? " " + length + " bytes" : "") + ">";
        }
        try {
            String s = response.asString();
            return s == null || s.isEmpty() ? null : cap(s);
        } catch (Throwable t) { return null; }
    }

    private static String cap(String s) {
        if (s == null || s.length() <= MAX_BODY) return s;
        return s.substring(0, MAX_BODY) + "\n… truncated (" + s.length() + " chars)";
    }

    private static Map<String, String> headers(Iterable<Header> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers != null) for (Header h : headers) out.put(h.getName(), h.getValue());
        return out;
    }
}
