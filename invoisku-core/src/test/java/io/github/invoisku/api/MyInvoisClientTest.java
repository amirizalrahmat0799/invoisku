package io.github.invoisku.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.invoisku.EInvoices;
import io.github.invoisku.TestData;
import io.github.invoisku.api.Records.DocumentDetails;
import io.github.invoisku.api.Records.SubmissionResult;
import io.github.invoisku.api.Records.SubmissionStatus;
import io.github.invoisku.codes.IdType;
import io.github.invoisku.sign.DocumentSigner;
import io.github.invoisku.sign.PropsDigestScope;
import io.github.invoisku.validate.Validator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MyInvoisClientTest {

    private MockMyInvois mock;
    private MyInvoisClient client;
    private final List<Duration> sleeps = new ArrayList<>();
    private final EInvoices einvoices = new EInvoices(
            new DocumentSigner(TestData.credentials(), TestData.CLOCK, PropsDigestScope.QUALIFYING_PROPERTIES),
            new Validator(TestData.CLOCK));

    @BeforeEach
    void start() throws Exception {
        mock = new MockMyInvois();
        client = MyInvoisClient.builder(MockMyInvois.CLIENT_ID, MockMyInvois.CLIENT_SECRET)
                .baseUrl(mock.url())
                .portalUrl("https://preprod.myinvois.hasil.gov.my")
                .sleeper(sleeps::add)
                .build();
    }

    @AfterEach
    void stop() {
        mock.close();
    }

    private SubmittedDocument signed(String number) {
        return einvoices.prepare(TestData.invoice().number(number).build()).submittedDocument();
    }

    @Test
    void submitsAndTracksADocument() {
        SubmissionResult r = client.submit(List.of(signed("INV-1")));
        assertEquals(26, r.submissionUid().length());
        assertEquals(1, r.accepted().size());
        assertEquals("INV-1", r.accepted().get(0).invoiceCodeNumber());
        assertEquals(List.of(), r.rejected());

        SubmissionStatus s = client.getSubmission(r.submissionUid());
        assertEquals("valid", s.overallStatus());
        assertFalse(s.isInProgress());
        Records.DocumentSummary doc = s.documents().get(0);
        assertEquals("Valid", doc.status());
        assertTrue(doc.isFinal());
        assertEquals("https://preprod.myinvois.hasil.gov.my/" + doc.uuid() + "/share/" + doc.longId(),
                client.validationLink(doc.uuid(), doc.longId()));
    }

    @Test
    void badSignatureShowsUpInTheDetails() {
        String json = einvoices.prepare(TestData.invoice().build()).json().replace("3179.90", "3179.00");
        SubmissionResult r = client.submit(List.of(SubmittedDocument.json("INV-X", json)));
        DocumentDetails d = client.getDocumentDetails(r.accepted().get(0).uuid());
        assertEquals("Invalid", d.summary().status());
        assertEquals("SignatureInvalid", d.validationSteps().get(0).errorCode());
        assertTrue(d.validationSteps().get(0).message().contains("DocDigest"));
    }

    @Test
    void reusesTheTokenAndRenewsItWhenRevoked() {
        client.submit(List.of(signed("INV-1")));
        client.submit(List.of(signed("INV-2")));
        assertEquals(1, mock.tokenRequests.get());
        mock.revokeTokens();
        client.submit(List.of(signed("INV-3")));
        assertEquals(2, mock.tokenRequests.get());
    }

    @Test
    void wrongCredentialsFail() {
        MyInvoisClient bad = MyInvoisClient.builder("x", "y").baseUrl(mock.url()).build();
        MyInvoisException e = assertThrows(MyInvoisException.class, bad::accessToken);
        assertEquals(400, e.status());
        assertEquals("invalid_client", e.code());
    }

    @Test
    void intermediariesSendOnBehalfOf() {
        MyInvoisClient agent = MyInvoisClient.builder(MockMyInvois.CLIENT_ID, MockMyInvois.CLIENT_SECRET)
                .baseUrl(mock.url()).onBehalfOf("C25845632020").build();
        agent.accessToken();
        assertEquals("C25845632020", mock.lastHeaders.get("onbehalfof"));
    }

    @Test
    void duplicatesAreNotRetried() {
        SubmittedDocument doc = signed("INV-1");
        client.submit(List.of(doc));
        DuplicateSubmissionException e = assertThrows(DuplicateSubmissionException.class,
                () -> client.submit(List.of(doc)));
        assertEquals(Duration.ofSeconds(120), e.retryAfter());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void throttlingIsRetriedHonouringRetryAfter() {
        mock.faults.add(new int[] {429, 7});
        mock.faults.add(new int[] {503, -1});
        SubmissionResult r = client.submit(List.of(signed("INV-1")));
        assertEquals(1, r.accepted().size());
        assertEquals(List.of(Duration.ofSeconds(7), Duration.ofMillis(1000)), sleeps);
    }

    @Test
    void givesUpAfterMaxRetries() {
        for (int i = 0; i < 4; i++) mock.faults.add(new int[] {500, -1});
        MyInvoisException e = assertThrows(MyInvoisException.class, () -> client.submit(List.of(signed("INV-1"))));
        assertEquals(500, e.status());
        assertEquals(3, sleeps.size());
    }

    @Test
    void clientErrorsAreNotRetried() {
        mock.faults.add(new int[] {400, -1});
        MyInvoisException e = assertThrows(MyInvoisException.class, () -> client.getSubmission("ABC"));
        assertEquals("Fault", e.code());
        assertFalse(e.isRetryable());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void batchesRespectLimits() {
        SubmittedDocument doc = signed("INV-1");
        List<SubmittedDocument> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) many.add(doc);
        List<List<SubmittedDocument>> batches = MyInvoisClient.batches(many);
        assertEquals(List.of(100, 100, 50), batches.stream().map(List::size).toList());

        SubmittedDocument big = new SubmittedDocument("JSON", "BIG", new byte[290 * 1024]);
        List<SubmittedDocument> heavy = new ArrayList<>();
        for (int i = 0; i < 30; i++) heavy.add(big);
        for (List<SubmittedDocument> b : MyInvoisClient.batches(heavy)) {
            long size = b.stream().mapToLong(SubmittedDocument::encodedSize).sum();
            assertTrue(size <= MyInvoisClient.MAX_SUBMISSION_BYTES, "batch of " + b.size() + " is " + size);
        }

        assertThrows(IllegalArgumentException.class, () -> client.submit(many));
        assertThrows(IllegalArgumentException.class,
                () -> client.submit(List.of(new SubmittedDocument("JSON", "HUGE", new byte[301 * 1024]))));
    }

    @Test
    void submitAllSplitsIntoSeveralSubmissions() {
        List<SubmittedDocument> docs = new ArrayList<>();
        for (int i = 0; i < 101; i++) docs.add(signed("INV-" + i));
        List<SubmissionResult> results = client.submitAll(docs);
        assertEquals(2, results.size());
        assertEquals(100, results.get(0).accepted().size());
        assertEquals(1, results.get(1).accepted().size());
    }

    @Test
    void cancelsAndRejects() {
        String uuid = client.submit(List.of(signed("INV-1"))).accepted().get(0).uuid();
        client.cancel(uuid, "Wrong buyer");
        assertTrue(mock.requests.contains("PUT /api/v1.0/documents/state/" + uuid + "/state"));
        MyInvoisException again = assertThrows(MyInvoisException.class, () -> client.cancel(uuid, "again"));
        assertEquals("IncorrectState", again.code());
        assertThrows(IllegalArgumentException.class, () -> client.reject(uuid, ""));
        assertThrows(IllegalArgumentException.class, () -> client.cancel("../../etc", "x"));
    }

    @Test
    void validatesTinsWithACache() {
        mock.knownTins.add("C25845632020");
        assertTrue(client.validateTin("C25845632020", IdType.BRN, "202001234567"));
        assertTrue(client.validateTin("C25845632020", IdType.BRN, "202001234567"));
        assertFalse(client.validateTin("C00000000000", IdType.BRN, "1"));
        long calls = mock.requests.stream().filter(r -> r.startsWith("GET /api/v1.0/taxpayer/validate/")).count();
        assertEquals(2, calls);
        assertTrue(mock.requests.contains("GET /api/v1.0/taxpayer/validate/C25845632020?idType=BRN&idValue=202001234567"));
    }

    @Test
    void searchesTins() {
        assertEquals("C25845632020", client.searchTin(IdType.BRN, "202001234567", null));
        assertNull(client.searchTin(IdType.BRN, "999", "Nobody"));
    }

    @Test
    void parsesRetryAfterForms() {
        assertEquals(Duration.ofSeconds(30), MyInvoisClient.parseRetryAfter("30"));
        assertEquals(Duration.ZERO, MyInvoisClient.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"));
        assertNull(MyInvoisClient.parseRetryAfter("soon"));
    }
}
