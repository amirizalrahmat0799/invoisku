package io.github.invoisku.api;

import io.github.invoisku.api.Records.AcceptedDocument;
import io.github.invoisku.api.Records.DocumentDetails;
import io.github.invoisku.api.Records.DocumentSummary;
import io.github.invoisku.api.Records.RejectedDocument;
import io.github.invoisku.api.Records.SubmissionResult;
import io.github.invoisku.api.Records.SubmissionStatus;
import io.github.invoisku.api.Records.ValidationStep;
import io.github.invoisku.codes.IdType;
import io.github.invoisku.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for the MyInvois e-invoicing API, using only {@code java.net.http}. Thread-safe; create one per client ID
 * and reuse it.
 *
 * <pre>{@code
 * MyInvoisClient api = MyInvoisClient.builder(clientId, clientSecret)
 *         .environment(Environment.SANDBOX)
 *         .build();
 * SubmissionResult r = api.submit(List.of(SubmittedDocument.json("INV-001", signedJson)));
 * }</pre>
 *
 * <p>Access tokens are fetched and refreshed automatically. Throttled (429) and server-error (5xx) responses are
 * retried with back-off, honouring Retry-After. Duplicate submissions are not retried; they raise
 * {@link DuplicateSubmissionException}.
 */
public final class MyInvoisClient {

    /** MyInvois limits for one submission request. */
    public static final int MAX_DOCUMENTS_PER_SUBMISSION = 100;
    public static final long MAX_SUBMISSION_BYTES = 5L * 1024 * 1024;

    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(2);
    private static final Duration TIN_CACHE_TTL = Duration.ofHours(24);

    private final URI apiBase;
    private final URI portalBase;
    private final String clientId;
    private final String clientSecret;
    private final String onBehalfOf;
    private final HttpClient http;
    private final Clock clock;
    private final Sleeper sleeper;
    private final int maxRetries;
    private final Duration timeout;

    private final Object tokenLock = new Object();
    private volatile String token;
    private volatile Instant tokenExpiry = Instant.EPOCH;
    private final Map<String, CachedTin> tinCache = new ConcurrentHashMap<>();

    private record CachedTin(boolean valid, Instant at) {}

    /** Pluggable sleep, so tests do not wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private MyInvoisClient(Builder b) {
        this.apiBase = b.apiBase != null ? b.apiBase : b.environment.apiBase();
        this.portalBase = b.portalBase != null ? b.portalBase : b.environment.portalBase();
        this.clientId = Objects.requireNonNull(b.clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(b.clientSecret, "clientSecret");
        this.onBehalfOf = b.onBehalfOf;
        this.http = b.http != null ? b.http
                : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.clock = b.clock;
        this.sleeper = b.sleeper;
        this.maxRetries = b.maxRetries;
        this.timeout = b.timeout;
    }

    public static Builder builder(String clientId, String clientSecret) {
        return new Builder(clientId, clientSecret);
    }

    // ------------------------------------------------------------------ auth

    /** A valid access token, logging in if the cached one is missing or about to expire. */
    public String accessToken() {
        Instant now = clock.instant();
        String t = token;
        if (t != null && now.isBefore(tokenExpiry.minus(TOKEN_MARGIN))) return t;
        synchronized (tokenLock) {
            if (token != null && now.isBefore(tokenExpiry.minus(TOKEN_MARGIN))) return token;
            String form = "client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                    + "&grant_type=client_credentials&scope=InvoicingAPI";
            HttpRequest.Builder req = HttpRequest.newBuilder(apiBase.resolve("/connect/token"))
                    .timeout(timeout)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form));
            if (onBehalfOf != null) req.header("onbehalfof", onBehalfOf);
            HttpResponse<String> res = send(req.build());
            if (res.statusCode() != 200) throw error(res);
            Map<String, Object> body = Json.parseObject(res.body());
            token = str(body, "access_token");
            Object expires = body.get("expires_in");
            long seconds = expires == null ? 3600 : Long.parseLong(expires.toString());
            tokenExpiry = clock.instant().plusSeconds(seconds);
            return token;
        }
    }

    // ------------------------------------------------------------------ submission

    /**
     * Submits up to 100 documents in one request. Throws {@link IllegalArgumentException} if the batch breaks
     * MyInvois' size limits; use {@link #submitAll} to split automatically.
     */
    public SubmissionResult submit(List<SubmittedDocument> documents) {
        checkBatch(documents);
        List<Object> docs = new ArrayList<>();
        for (SubmittedDocument d : documents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", d.format());
            m.put("document", d.base64());
            m.put("documentHash", d.sha256Hex());
            m.put("codeNumber", d.codeNumber());
            docs.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documents", docs);
        HttpResponse<String> res = call("POST", "/api/v1.0/documentsubmissions/", Json.write(body));
        Map<String, Object> json = Json.parseObject(res.body());
        List<AcceptedDocument> accepted = new ArrayList<>();
        for (Map<String, Object> a : maps(json.get("acceptedDocuments"))) {
            accepted.add(new AcceptedDocument(str(a, "uuid"), str(a, "invoiceCodeNumber")));
        }
        List<RejectedDocument> rejected = new ArrayList<>();
        for (Map<String, Object> r : maps(json.get("rejectedDocuments"))) {
            Map<String, Object> err = map(r.get("error"));
            rejected.add(new RejectedDocument(str(r, "invoiceCodeNumber"), str(err, "code"), str(err, "message"),
                    detailMessages(err)));
        }
        return new SubmissionResult(str(json, "submissionUid"), accepted, rejected);
    }

    /** Splits documents into batches that respect MyInvois limits and submits each. */
    public List<SubmissionResult> submitAll(List<SubmittedDocument> documents) {
        List<SubmissionResult> results = new ArrayList<>();
        for (List<SubmittedDocument> batch : batches(documents)) results.add(submit(batch));
        return results;
    }

    /** Groups documents into batches of at most 100 documents and 5 MB. */
    public static List<List<SubmittedDocument>> batches(List<SubmittedDocument> documents) {
        List<List<SubmittedDocument>> out = new ArrayList<>();
        List<SubmittedDocument> current = new ArrayList<>();
        long size = 32;
        for (SubmittedDocument d : documents) {
            checkDocument(d);
            if (!current.isEmpty() && (current.size() == MAX_DOCUMENTS_PER_SUBMISSION
                    || size + d.encodedSize() > MAX_SUBMISSION_BYTES)) {
                out.add(current);
                current = new ArrayList<>();
                size = 32;
            }
            current.add(d);
            size += d.encodedSize();
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private static void checkBatch(List<SubmittedDocument> documents) {
        if (documents.isEmpty()) throw new IllegalArgumentException("Nothing to submit");
        if (documents.size() > MAX_DOCUMENTS_PER_SUBMISSION) {
            throw new IllegalArgumentException("At most 100 documents per submission; use submitAll()");
        }
        long size = 32;
        for (SubmittedDocument d : documents) {
            checkDocument(d);
            size += d.encodedSize();
        }
        if (size > MAX_SUBMISSION_BYTES) {
            throw new IllegalArgumentException("Submission is over 5 MB; use submitAll()");
        }
    }

    private static void checkDocument(SubmittedDocument d) {
        if (d.content().length > SubmittedDocument.MAX_BYTES) {
            throw new IllegalArgumentException("Document " + d.codeNumber() + " is " + d.content().length
                    + " bytes; MyInvois accepts at most 300 KB per document");
        }
    }

    /** Status of a submission and its documents. Poll until {@link SubmissionStatus#isInProgress()} is false. */
    public SubmissionStatus getSubmission(String submissionUid) {
        return getSubmission(submissionUid, 1, 100);
    }

    public SubmissionStatus getSubmission(String submissionUid, int page, int pageSize) {
        HttpResponse<String> res = call("GET", "/api/v1.0/documentsubmissions/" + path(submissionUid)
                + "?pageNo=" + page + "&pageSize=" + pageSize, null);
        Map<String, Object> json = Json.parseObject(res.body());
        List<DocumentSummary> docs = new ArrayList<>();
        for (Map<String, Object> d : maps(json.get("documentSummary"))) docs.add(summary(d));
        Object count = json.get("documentCount");
        return new SubmissionStatus(str(json, "submissionUid"), count == null ? docs.size()
                : Integer.parseInt(count.toString()), str(json, "dateTimeReceived"), str(json, "overallStatus"), docs);
    }

    /** Document summary plus LHDN's validation results (why a document is Invalid). */
    public DocumentDetails getDocumentDetails(String uuid) {
        HttpResponse<String> res = call("GET", "/api/v1.0/documents/" + path(uuid) + "/details", null);
        Map<String, Object> json = Json.parseObject(res.body());
        List<ValidationStep> steps = new ArrayList<>();
        Map<String, Object> vr = map(json.get("validationResults"));
        for (Map<String, Object> s : maps(vr.get("validationSteps"))) {
            Map<String, Object> err = map(s.get("error"));
            steps.add(new ValidationStep(str(s, "name"), str(s, "status"), str(err, "errorCode"),
                    err.containsKey("error") ? str(err, "error") : str(err, "message"), detailMessages(err)));
        }
        return new DocumentDetails(summary(json), steps, res.body());
    }

    /** The document as LHDN stored it, with its summary fields; JSON text. */
    public String getDocumentRaw(String uuid) {
        return call("GET", "/api/v1.0/documents/" + path(uuid) + "/raw", null).body();
    }

    // ------------------------------------------------------------------ state changes

    /** Cancels a Valid document you issued. Allowed within 72 hours of validation. */
    public void cancel(String uuid, String reason) {
        changeState(uuid, "cancelled", reason);
    }

    /** Asks the issuer to cancel a document you received (buyer side). Allowed within 72 hours of validation. */
    public void reject(String uuid, String reason) {
        changeState(uuid, "rejected", reason);
    }

    private void changeState(String uuid, String status, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw new IllegalArgumentException("A reason of 1 to 300 characters is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("reason", reason);
        call("PUT", "/api/v1.0/documents/state/" + path(uuid) + "/state", Json.write(body));
    }

    // ------------------------------------------------------------------ taxpayers

    /**
     * Whether a TIN belongs to the given registration number. Results are cached for 24 hours, as LHDN asks
     * clients to do.
     */
    public boolean validateTin(String tin, IdType idType, String idValue) {
        String key = tin + "|" + idType + "|" + idValue;
        CachedTin cached = tinCache.get(key);
        if (cached != null && clock.instant().isBefore(cached.at().plus(TIN_CACHE_TTL))) return cached.valid();
        boolean valid;
        try {
            call("GET", "/api/v1.0/taxpayer/validate/" + path(tin) + "?idType=" + enc(idType.name())
                    + "&idValue=" + enc(idValue), null);
            valid = true;
        } catch (MyInvoisException e) {
            if (e.status() != 404 && e.status() != 400) throw e;
            valid = false;
        }
        tinCache.put(key, new CachedTin(valid, clock.instant()));
        return valid;
    }

    /** Finds a taxpayer's TIN by registration number (and optionally name). Returns null if not found. */
    public String searchTin(IdType idType, String idValue, String taxpayerName) {
        StringJoiner q = new StringJoiner("&", "?", "");
        if (idType != null) q.add("idType=" + enc(idType.name())).add("idValue=" + enc(idValue));
        if (taxpayerName != null) q.add("taxpayerName=" + enc(taxpayerName));
        try {
            Map<String, Object> json = Json.parseObject(call("GET", "/api/v1.0/taxpayer/search/tin" + q, null).body());
            return str(json, "tin");
        } catch (MyInvoisException e) {
            if (e.status() == 404) return null;
            throw e;
        }
    }

    // ------------------------------------------------------------------ search

    /**
     * Documents issued or received in the last 31 days. Filters use MyInvois' query names, for example
     * {@code Map.of("InvoiceDirection", "Sent", "Status", "Valid")}.
     */
    public List<DocumentSummary> recentDocuments(Map<String, String> filters, int page, int pageSize) {
        StringJoiner q = new StringJoiner("&", "?", "");
        q.add("pageNo=" + page).add("pageSize=" + pageSize);
        filters.forEach((k, v) -> q.add(enc(k) + "=" + enc(v)));
        Map<String, Object> json = Json.parseObject(call("GET", "/api/v1.0/documents/recent" + q, null).body());
        List<DocumentSummary> out = new ArrayList<>();
        for (Map<String, Object> d : maps(json.get("result"))) out.add(summary(d));
        return out;
    }

    /** The public validation link for a Valid document; print it as the e-invoice's QR code. */
    public String validationLink(String uuid, String longId) {
        return portalBase.resolve("/" + path(uuid) + "/share/" + path(longId)).toString();
    }

    // ------------------------------------------------------------------ http

    private HttpResponse<String> call(String method, String pathAndQuery, String jsonBody) {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder req = HttpRequest.newBuilder(apiBase.resolve(pathAndQuery))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en");
            if (jsonBody != null) {
                req.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                req.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> res;
            try {
                res = send(req.build());
            } catch (MyInvoisException e) {
                if (attempt >= maxRetries) throw e;
                pause(backoff(attempt, null));
                continue;
            }
            int s = res.statusCode();
            if (s >= 200 && s < 300) return res;
            if (s == 401 && attempt == 0) {
                synchronized (tokenLock) {
                    token = null;
                }
                continue;
            }
            MyInvoisException e = error(res);
            if (!e.isRetryable() || attempt >= maxRetries) throw e;
            pause(backoff(attempt, e.retryAfter()));
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MyInvoisException("Could not reach MyInvois: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MyInvoisException("Interrupted", e);
        }
    }

    private static Duration backoff(int attempt, Duration retryAfter) {
        if (retryAfter != null) return retryAfter;
        return Duration.ofMillis(Math.min(30_000, 500L << attempt));
    }

    private void pause(Duration d) {
        try {
            sleeper.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MyInvoisException("Interrupted", e);
        }
    }

    static MyInvoisException error(HttpResponse<String> res) {
        Duration retryAfter = res.headers().firstValue("Retry-After").map(MyInvoisClient::parseRetryAfter)
                .orElse(null);
        String code = null;
        String message = res.body() == null || res.body().isBlank() ? "HTTP " + res.statusCode() : res.body();
        List<String> details = List.of();
        try {
            Map<String, Object> json = Json.parseObject(res.body());
            Object err = json.get("error");
            if (err instanceof Map) {
                Map<String, Object> e = map(err);
                code = str(e, "code");
                message = str(e, "message") != null ? str(e, "message") : message;
                details = detailMessages(e);
            } else if (err instanceof String s) {
                code = s;
                Object desc = json.get("error_description");
                message = desc == null ? s : desc.toString();
            } else if (json.containsKey("message")) {
                message = String.valueOf(json.get("message"));
            }
        } catch (RuntimeException ignored) {
            // not JSON; keep the raw body
        }
        if (res.statusCode() == 422 && (code == null || "DuplicateSubmission".equalsIgnoreCase(code))) {
            return new DuplicateSubmissionException(message, details, retryAfter);
        }
        return new MyInvoisException(res.statusCode(), code, message, details, retryAfter);
    }

    static Duration parseRetryAfter(String v) {
        try {
            return Duration.ofSeconds(Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            try {
                Instant at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration d = Duration.between(Instant.now(), at);
                return d.isNegative() ? Duration.ZERO : d;
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ json helpers

    private static DocumentSummary summary(Map<String, Object> d) {
        return new DocumentSummary(str(d, "uuid"), str(d, "submissionUid"), str(d, "longId"), str(d, "internalId"),
                str(d, "typeName"), str(d, "typeVersionName"), str(d, "issuerTin"), str(d, "receiverId"),
                str(d, "dateTimeIssued"), str(d, "dateTimeValidated"), str(d, "totalPayableAmount"),
                str(d, "status"));
    }

    private static List<String> detailMessages(Map<String, Object> err) {
        List<String> out = new ArrayList<>();
        for (String key : new String[] {"details", "innerError"}) {
            for (Map<String, Object> d : maps(err.get(key))) {
                String m = d.containsKey("message") ? str(d, "message") : str(d, "error");
                String target = d.containsKey("target") ? str(d, "target") : str(d, "propertyPath");
                out.add(target == null ? String.valueOf(m) : target + ": " + m);
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) if (x instanceof Map) out.add((Map<String, Object>) x);
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String path(String segment) {
        if (segment == null || segment.isEmpty() || !segment.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid identifier: " + segment);
        }
        return segment;
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private final String clientId;
        private final String clientSecret;
        private Environment environment = Environment.SANDBOX;
        private URI apiBase;
        private URI portalBase;
        private String onBehalfOf;
        private HttpClient http;
        private Clock clock = Clock.systemUTC();
        private Sleeper sleeper = d -> Thread.sleep(d.toMillis());
        private int maxRetries = 3;
        private Duration timeout = Duration.ofSeconds(30);

        private Builder(String clientId, String clientSecret) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public Builder environment(Environment env) {
            this.environment = env;
            return this;
        }

        /** Overrides the API host, for a mock server or a proxy. */
        public Builder baseUrl(String url) {
            this.apiBase = URI.create(url);
            return this;
        }

        public Builder portalUrl(String url) {
            this.portalBase = URI.create(url);
            return this;
        }

        /** For intermediaries (ERP vendors, accountants): the TIN of the taxpayer you act for. */
        public Builder onBehalfOf(String taxpayerTin) {
            this.onBehalfOf = taxpayerTin;
            return this;
        }

        public Builder httpClient(HttpClient http) {
            this.http = http;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder sleeper(Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        public Builder maxRetries(int n) {
            this.maxRetries = n;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public MyInvoisClient build() {
            return new MyInvoisClient(this);
        }
    }
}
