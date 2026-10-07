package io.github.invoisku.sign;

import io.github.invoisku.json.Json;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Checks a signed UBL-JSON e-invoice: the document digest, the RSA signature, the certificate digest and the
 * signed-properties digest. Use it on your own output before submitting, or on LHDN's signed samples to confirm
 * the exact hashing rules.
 */
public final class SignatureVerifier {

    private SignatureVerifier() {}

    /**
     * @param propsDigestScope which JSON matched the SignedProperties digest, or null if none did
     */
    public record Report(
            boolean docDigestMatches,
            boolean signatureValid,
            boolean certDigestMatches,
            PropsDigestScope propsDigestScope,
            X509Certificate certificate,
            String signingTime,
            List<String> problems) {

        public boolean isValid() {
            return docDigestMatches && signatureValid && certDigestMatches && propsDigestScope != null;
        }
    }

    public static Report verify(String signedJson) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> root = Json.parseObject(signedJson);
        Map<String, Object> doc = DocumentSigner.document(root);

        Map<String, Object> sig;
        try {
            Map<String, Object> ext = first(doc, "UBLExtensions", "UBLExtension");
            Map<String, Object> content = first(ext, "ExtensionContent", "UBLDocumentSignatures",
                    "SignatureInformation");
            sig = first(content, "Signature");
        } catch (RuntimeException e) {
            problems.add("No XAdES signature found in UBLExtensions");
            return new Report(false, false, false, null, null, null, problems);
        }

        try {
            Map<String, Object> qualifying = first(sig, "Object", "QualifyingProperties");
            Map<String, Object> signedProps = first(qualifying, "SignedProperties");
            Map<String, Object> ssp = first(signedProps, "SignedSignatureProperties");
            String signingTime = value(first(ssp, "SigningTime"));
            String certDigestClaimed = value(first(first(first(ssp, "SigningCertificate", "Cert"), "CertDigest"),
                    "DigestValue"));

            String certB64 = value(first(sig, "KeyInfo", "X509Data", "X509Certificate"));
            byte[] certDer = Base64.getMimeDecoder().decode(certB64);
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certDer));

            String docDigestClaimed = null;
            String propsDigestClaimed = null;
            for (Object o : list(first(sig, "SignedInfo"), "Reference")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ref = (Map<String, Object>) o;
                String digest = value(first(ref, "DigestValue"));
                if (String.valueOf(ref.get("URI")).isEmpty()) docDigestClaimed = digest;
                else propsDigestClaimed = digest;
            }
            String signatureValue = value(first(sig, "SignatureValue"));

            Map<String, Object> stripped = Json.deepCopy(root);
            Map<String, Object> strippedDoc = DocumentSigner.document(stripped);
            strippedDoc.remove("UBLExtensions");
            strippedDoc.remove("Signature");
            byte[] body = Json.write(stripped).getBytes(StandardCharsets.UTF_8);

            boolean docOk = DocumentSigner.b64(DocumentSigner.sha256(body)).equals(docDigestClaimed);
            if (!docOk) problems.add("DocDigest does not match the document (was it changed after signing?)");

            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(cert.getPublicKey());
            rsa.update(body);
            boolean sigOk = rsa.verify(Base64.getMimeDecoder().decode(signatureValue));
            if (!sigOk) problems.add("SignatureValue is not a valid RSA-SHA256 signature of the document");

            boolean certOk = DocumentSigner.b64(DocumentSigner.sha256(certDer)).equals(certDigestClaimed);
            if (!certOk) problems.add("CertDigest does not match the embedded certificate");

            PropsDigestScope scope = null;
            for (PropsDigestScope s : PropsDigestScope.values()) {
                if (DocumentSigner.propsDigest(qualifying, signedProps, s).equals(propsDigestClaimed)) {
                    scope = s;
                    break;
                }
            }
            if (scope == null) problems.add("Signed properties digest matches neither known hashing scope");

            return new Report(docOk, sigOk, certOk, scope, cert, signingTime, problems);
        } catch (GeneralSecurityException | RuntimeException e) {
            problems.add("Malformed signature: " + e.getMessage());
            return new Report(false, false, false, null, null, null, problems);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(Map<String, Object> node, String... path) {
        Map<String, Object> cur = node;
        for (String key : path) {
            Object v = cur.get(key);
            if (!(v instanceof List<?> l) || l.isEmpty() || !(l.get(0) instanceof Map)) {
                throw new IllegalArgumentException("Missing " + key);
            }
            cur = (Map<String, Object>) l.get(0);
        }
        return cur;
    }

    private static List<?> list(Map<String, Object> node, String key) {
        Object v = node.get(key);
        if (!(v instanceof List<?> l)) throw new IllegalArgumentException("Missing " + key);
        return l;
    }

    private static String value(Map<String, Object> node) {
        Object v = node.get("_");
        if (v == null) throw new IllegalArgumentException("Missing value");
        return v.toString();
    }
}
