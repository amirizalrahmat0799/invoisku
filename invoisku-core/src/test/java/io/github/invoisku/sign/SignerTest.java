package io.github.invoisku.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.invoisku.TestData;
import io.github.invoisku.json.Json;
import io.github.invoisku.ubl.UblJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignerTest {

    private final DocumentSigner signer = new DocumentSigner(TestData.credentials(), TestData.CLOCK,
            PropsDigestScope.QUALIFYING_PROPERTIES);

    private String unsigned() {
        return UblJson.toJson(TestData.invoice().build());
    }

    @Test
    void signedDocumentVerifies() {
        SignatureVerifier.Report r = SignatureVerifier.verify(signer.sign(unsigned()));
        assertTrue(r.isValid(), r.problems().toString());
        assertEquals(PropsDigestScope.QUALIFYING_PROPERTIES, r.propsDigestScope());
        assertEquals("2026-10-01T02:00:00Z", r.signingTime());
        assertEquals("CN=INVOISKU TEST SDN BHD, O=Invoisku Test, C=MY",
                r.certificate().getSubjectX500Principal().toString());
    }

    @Test
    void verifierDetectsTheOtherPropsScope() {
        DocumentSigner other = new DocumentSigner(TestData.credentials(), TestData.CLOCK,
                PropsDigestScope.SIGNED_PROPERTIES);
        assertEquals(PropsDigestScope.SIGNED_PROPERTIES,
                SignatureVerifier.verify(other.sign(unsigned())).propsDigestScope());
    }

    @Test
    @SuppressWarnings("unchecked")
    void placesExtensionsFirstAndSignatureBeforeTheSupplier() {
        Map<String, Object> root = Json.parseObject(signer.sign(unsigned()));
        Map<String, Object> doc = (Map<String, Object>) ((List<Object>) root.get("Invoice")).get(0);
        List<String> keys = new ArrayList<>(doc.keySet());
        assertEquals("UBLExtensions", keys.get(0));
        assertEquals(keys.indexOf("AccountingSupplierParty") - 1, keys.indexOf("Signature"));
        assertEquals("[{\"ID\":[{\"_\":\"urn:oasis:names:specification:ubl:signature:Invoice\"}],"
                + "\"SignatureMethod\":[{\"_\":\"urn:oasis:names:specification:ubl:dsig:enveloped:xades\"}]}]",
                Json.write(doc.get("Signature")));
    }

    @Test
    void signingIsDeterministicAndRepeatable() {
        String once = signer.sign(unsigned());
        assertEquals(once, signer.sign(unsigned()));
        // re-signing a signed document replaces the old signature rather than nesting it
        assertEquals(once, signer.sign(once));
    }

    @Test
    void tamperingIsDetected() {
        String signed = signer.sign(unsigned());
        SignatureVerifier.Report r = SignatureVerifier.verify(signed.replace("3179.90", "3179.00"));
        assertFalse(r.isValid());
        assertFalse(r.docDigestMatches());
        assertFalse(r.signatureValid());
        assertTrue(r.certDigestMatches());
    }

    @Test
    void whitespaceDoesNotMatterButContentDoes() {
        String signed = signer.sign(unsigned());
        String pretty = signed.replace(",\"", ",\n  \"");
        assertTrue(SignatureVerifier.verify(pretty).isValid());
    }

    @Test
    void unsignedDocumentIsReported() {
        SignatureVerifier.Report r = SignatureVerifier.verify(unsigned());
        assertFalse(r.isValid());
        assertEquals(List.of("No XAdES signature found in UBLExtensions"), r.problems());
    }

    @Test
    void loadsPemKeysInBothFormats() throws Exception {
        String cert = TestData.resource("/certs/cert.pem");
        for (String key : List.of("/certs/key8.pem", "/certs/key1.pem")) {
            SigningCredentials c = SigningCredentials.fromPem(cert, TestData.resource(key));
            assertNotNull(c.key());
            String signed = new DocumentSigner(c).sign(unsigned());
            assertTrue(SignatureVerifier.verify(signed).isValid(), key);
        }
    }
}
