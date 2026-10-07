package io.github.invoisku.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.invoisku.json.Json;
import io.github.invoisku.sign.SignatureVerifier;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for the MyInvois API, built on the JDK's HTTP server. It checks what a real server checks
 * first (token, hash, size, duplicate payloads, signature) and keeps document state in memory.
 */
final class MockMyInvois implements AutoCloseable {

    static final String CLIENT_ID = "test-client";
    static final String CLIENT_SECRET = "test-secret";

    private final HttpServer server;
    final AtomicInteger tokenRequests = new AtomicInteger();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    final Set<String> knownTins = ConcurrentHashMap.newKeySet();
    /** Forced responses for the next API calls: status, Retry-After seconds (or -1). */
    final Deque<int[]> faults = new ArrayDeque<>();

    private final Map<String, Map<String, Object>> submissions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> documents = new ConcurrentHashMap<>();
    private final Map<String, String> payloadHashes = new ConcurrentHashMap<>();
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();

    MockMyInvois() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Invalidates every issued token, as if they expired on the server. */
    void revokeTokens() {
        tokens.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            requests.add(method + " " + ex.getRequestURI());
            ex.getRequestHeaders().forEach((k, v) -> lastHeaders.put(k.toLowerCase(), v.get(0)));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            if (path.equals("/connect/token")) {
                token(ex, body);
                return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !tokens.contains(auth.replace("Bearer ", ""))) {
                send(ex, 401, "", null);
                return;
            }
            int[] fault;
            synchronized (faults) {
                fault = faults.poll();
            }
            if (fault != null) {
                send(ex, fault[0], "{\"error\":{\"code\":\"Fault\",\"message\":\"injected\"}}",
                        fault[1] >= 0 ? String.valueOf(fault[1]) : null);
                return;
            }

            if (method.equals("POST") && path.equals("/api/v1.0/documentsubmissions/")) {
                submit(ex, body);
            } else if (method.equals("GET") && path.startsWith("/api/v1.0/documentsubmissions/")) {
                Map<String, Object> s = submissions.get(path.substring(path.lastIndexOf('/') + 1));
                if (s == null) send(ex, 404, "", null);
                else send(ex, 200, Json.write(s), null);
            } else if (method.equals("GET") && path.matches("/api/v1.0/documents/[^/]+/details")) {
                Map<String, Object> d = documents.get(path.split("/")[4]);
                if (d == null) send(ex, 404, "", null);
                else send(ex, 200, Json.write(d), null);
            } else if (method.equals("PUT") && path.matches("/api/v1.0/documents/state/[^/]+/state")) {
                changeState(ex, path.split("/")[5], body);
            } else if (method.equals("GET") && path.startsWith("/api/v1.0/taxpayer/validate/")) {
                String tin = path.substring(path.lastIndexOf('/') + 1);
                send(ex, knownTins.contains(tin) ? 200 : 404, "", null);
            } else if (method.equals("GET") && path.equals("/api/v1.0/taxpayer/search/tin")) {
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                if ("202001234567".equals(q.get("idValue"))) send(ex, 200, "{\"tin\":\"C25845632020\"}", null);
                else send(ex, 404, "", null);
            } else {
                send(ex, 404, "", null);
            }
        } catch (Exception e) {
            send(ex, 500, "{\"error\":{\"code\":\"Mock\",\"message\":\"" + e + "\"}}", null);
        }
    }

    private void token(HttpExchange ex, String body) throws IOException {
        tokenRequests.incrementAndGet();
        Map<String, String> form = query(body);
        if (!CLIENT_ID.equals(form.get("client_id")) || !CLIENT_SECRET.equals(form.get("client_secret"))
                || !"client_credentials".equals(form.get("grant_type"))) {
            send(ex, 400, "{\"error\":\"invalid_client\"}", null);
            return;
        }
        String t = UUID.randomUUID().toString();
        tokens.add(t);
        send(ex, 200, "{\"access_token\":\"" + t + "\",\"expires_in\":3600,\"token_type\":\"Bearer\","
                + "\"scope\":\"InvoicingAPI\"}", null);
    }

    @SuppressWarnings("unchecked")
    private void submit(HttpExchange ex, String body) throws Exception {
        String payloadHash = sha256(body.getBytes(StandardCharsets.UTF_8));
        if (payloadHashes.containsKey(payloadHash)) {
            send(ex, 422, "{\"error\":{\"code\":\"DuplicateSubmission\",\"message\":\"Duplicate submission\"}}", "120");
            return;
        }
        payloadHashes.put(payloadHash, "");
        List<Object> docs = (List<Object>) Json.parseObject(body).get("documents");
        if (docs == null || docs.isEmpty() || docs.size() > 100 || body.length() > 5 * 1024 * 1024) {
            send(ex, 400, "{\"error\":{\"code\":\"BadStructure\",\"message\":\"Bad submission\"}}", null);
            return;
        }
        String uid = random26();
        List<Object> accepted = new ArrayList<>();
        List<Object> rejected = new ArrayList<>();
        List<Object> summaries = new ArrayList<>();
        for (Object o : docs) {
            Map<String, Object> d = (Map<String, Object>) o;
            String code = String.valueOf(d.get("codeNumber"));
            byte[] content = Base64.getDecoder().decode(String.valueOf(d.get("document")));
            if (!sha256(content).equals(d.get("documentHash"))) {
                rejected.add(Map.of("invoiceCodeNumber", code, "error",
                        Map.of("code", "HashMismatch", "message", "Document hash does not match")));
                continue;
            }
            String uuid = random26();
            accepted.add(Map.of("uuid", uuid, "invoiceCodeNumber", code));
            String json = new String(content, StandardCharsets.UTF_8);
            SignatureVerifier.Report r = SignatureVerifier.verify(json);
            boolean v10 = json.contains("\"listVersionID\":\"1.0\"");
            String status = v10 || r.isValid() ? "Valid" : "Invalid";
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("uuid", uuid);
            summary.put("submissionUid", uid);
            summary.put("longId", status.equals("Valid") ? "LIJAF97HJJKH8298KHADH09908570FDKK9S2LSIUHB377373" : "");
            summary.put("internalId", code);
            summary.put("typeName", "Invoice");
            summary.put("typeVersionName", v10 ? "1.0" : "1.1");
            summary.put("status", status);
            summaries.add(summary);
            Map<String, Object> details = new LinkedHashMap<>(summary);
            List<Object> steps = new ArrayList<>();
            if (!status.equals("Valid")) {
                steps.add(Map.of("name", "Step06-Signature Validator", "status", "Invalid", "error",
                        Map.of("errorCode", "SignatureInvalid", "error", String.join("; ", r.problems()))));
            }
            details.put("validationResults", Map.of("status", status, "validationSteps", steps));
            documents.put(uuid, details);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("submissionUid", uid);
        s.put("documentCount", summaries.size());
        s.put("dateTimeReceived", "2026-10-01T02:00:00Z");
        s.put("overallStatus", summaries.stream().allMatch(x -> "Valid".equals(((Map<?, ?>) x).get("status")))
                ? "valid" : "partially valid");
        s.put("documentSummary", summaries);
        submissions.put(uid, s);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("submissionUid", uid);
        res.put("acceptedDocuments", accepted);
        res.put("rejectedDocuments", rejected);
        send(ex, 202, Json.write(res), null);
    }

    @SuppressWarnings("unchecked")
    private void changeState(HttpExchange ex, String uuid, String body) throws IOException {
        Map<String, Object> d = documents.get(uuid);
        if (d == null) {
            send(ex, 404, "", null);
            return;
        }
        Map<String, Object> req = Json.parseObject(body);
        if (!"Valid".equals(d.get("status"))) {
            send(ex, 400, "{\"error\":{\"code\":\"IncorrectState\",\"message\":\"Document is not Valid\"}}", null);
            return;
        }
        d.put("status", "cancelled".equals(req.get("status")) ? "Cancelled" : "Rejected");
        send(ex, 200, "{\"uuid\":\"" + uuid + "\",\"status\":\"" + d.get("status") + "\"}", null);
    }

    private static void send(HttpExchange ex, int status, String body, String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) ex.getResponseHeaders().add("Retry-After", retryAfter);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) {
                m.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String random26() {
        String chars = "ABCDEFGHJKMNPQRSTVWXYZ0123456789";
        StringBuilder s = new StringBuilder();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 26; i++) s.append(chars.charAt(r.nextInt(chars.length())));
        return s.toString();
    }
}
