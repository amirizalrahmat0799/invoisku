package io.github.invoisku.sign;

import static io.github.invoisku.ubl.UblJson.obj;
import static io.github.invoisku.ubl.UblJson.one;
import static io.github.invoisku.ubl.UblJson.v;

import io.github.invoisku.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs a UBL-JSON e-invoice the way MyInvois version 1.1 requires (enveloped XAdES, expressed in JSON).
 *
 * <ol>
 *   <li>Remove {@code UBLExtensions} and {@code Signature} from the document and minify it.</li>
 *   <li>DocDigest = base64(SHA-256(document)).</li>
 *   <li>SignatureValue = base64(RSA-SHA256(document)) with the taxpayer's private key.</li>
 *   <li>CertDigest = base64(SHA-256(certificate DER)).</li>
 *   <li>Build SignedProperties (signing time, certificate digest, issuer name and serial number);
 *       PropsDigest = base64(SHA-256(minified SignedProperties)).</li>
 *   <li>Insert {@code UBLExtensions} (first) and {@code Signature} (in UBL order) into the document.</li>
 * </ol>
 *
 * <p>The returned document must be sent exactly as returned: any change to the bytes outside the signature breaks
 * DocDigest.
 */
public final class DocumentSigner {

    static final String SHA256_URI = "http://www.w3.org/2001/04/xmlenc#sha256";
    static final String RSA_SHA256_URI = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    static final String XADES_URI = "urn:oasis:names:specification:ubl:dsig:enveloped:xades";
    static final String SIGNATURE_ID = "urn:oasis:names:specification:ubl:signature:Invoice";
    static final String SIGNED_PROPS_TYPE = "http://uri.etsi.org/01903/v1.3.2#SignedProperties";
    static final String SIGNED_PROPS_ID = "id-xades-signed-props";
    private static final DateTimeFormatter SIGNING_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final SigningCredentials credentials;
    private final Clock clock;
    private final PropsDigestScope propsScope;

    public DocumentSigner(SigningCredentials credentials) {
        this(credentials, Clock.systemUTC(), PropsDigestScope.QUALIFYING_PROPERTIES);
    }

    public DocumentSigner(SigningCredentials credentials, Clock clock, PropsDigestScope propsScope) {
        this.credentials = credentials;
        this.clock = clock;
        this.propsScope = propsScope;
    }

    /** Signs JSON text and returns the minified signed document. */
    public String sign(String unsignedJson) {
        return Json.write(sign(Json.parseObject(unsignedJson)));
    }

    /** Returns a signed copy of a UBL-JSON tree; the input is not modified. */
    public Map<String, Object> sign(Map<String, Object> unsigned) {
        try {
            Map<String, Object> root = Json.deepCopy(unsigned);
            Map<String, Object> doc = document(root);
            doc.remove("UBLExtensions");
            doc.remove("Signature");

            byte[] body = Json.write(root).getBytes(StandardCharsets.UTF_8);
            String docDigest = b64(sha256(body));

            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(credentials.key());
            rsa.update(body);
            String signatureValue = b64(rsa.sign());

            X509Certificate cert = credentials.certificate();
            String certDigest = b64(sha256(cert.getEncoded()));
            String signingTime = SIGNING_TIME.format(
                    OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS));

            Map<String, Object> signedProperties = signedProperties(signingTime, certDigest, cert);
            Map<String, Object> qualifying = new LinkedHashMap<>();
            qualifying.put("Target", "signature");
            qualifying.put("SignedProperties", one(signedProperties));
            String propsDigest = propsDigest(qualifying, signedProperties, propsScope);

            Map<String, Object> signed = new LinkedHashMap<>();
            signed.put("UBLExtensions", ublExtensions(qualifying, cert, signatureValue, propsDigest, docDigest));
            for (Map.Entry<String, Object> e : doc.entrySet()) {
                if (e.getKey().equals("AccountingSupplierParty")) signed.put("Signature", signatureElement());
                signed.put(e.getKey(), e.getValue());
            }
            if (!signed.containsKey("Signature")) signed.put("Signature", signatureElement());
            doc.clear();
            doc.putAll(signed);
            return root;
        } catch (GeneralSecurityException e) {
            throw new SigningException("Could not sign the document: " + e.getMessage(), e);
        }
    }

    static String propsDigest(Map<String, Object> qualifying, Map<String, Object> signedProperties,
            PropsDigestScope scope) throws GeneralSecurityException {
        Object target = scope == PropsDigestScope.QUALIFYING_PROPERTIES ? qualifying : signedProperties;
        return b64(sha256(Json.write(target).getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> signedProperties(String signingTime, String certDigest, X509Certificate cert) {
        Map<String, Object> digestMethod = new LinkedHashMap<>();
        digestMethod.put("_", "");
        digestMethod.put("Algorithm", SHA256_URI);
        Map<String, Object> certDigestEl = new LinkedHashMap<>();
        certDigestEl.put("DigestMethod", one(digestMethod));
        certDigestEl.put("DigestValue", v(certDigest));
        Map<String, Object> issuerSerial = new LinkedHashMap<>();
        issuerSerial.put("X509IssuerName", v(issuerName(cert)));
        issuerSerial.put("X509SerialNumber", v(cert.getSerialNumber().toString()));
        Map<String, Object> certEl = new LinkedHashMap<>();
        certEl.put("CertDigest", one(certDigestEl));
        certEl.put("IssuerSerial", one(issuerSerial));
        Map<String, Object> ssp = new LinkedHashMap<>();
        ssp.put("SigningTime", v(signingTime));
        ssp.put("SigningCertificate", one(obj("Cert", one(certEl))));
        Map<String, Object> sp = new LinkedHashMap<>();
        sp.put("Id", SIGNED_PROPS_ID);
        sp.put("SignedSignatureProperties", one(ssp));
        return sp;
    }

    private static List<Object> ublExtensions(Map<String, Object> qualifying, X509Certificate cert,
            String signatureValue, String propsDigest, String docDigest) throws CertificateEncodingException {
        Map<String, Object> x509 = new LinkedHashMap<>();
        x509.put("X509Certificate", v(Base64.getEncoder().encodeToString(cert.getEncoded())));
        x509.put("X509SubjectName", v(cert.getSubjectX500Principal().toString()));
        Map<String, Object> keyIssuer = new LinkedHashMap<>();
        keyIssuer.put("X509IssuerName", v(issuerName(cert)));
        keyIssuer.put("X509SerialNumber", v(cert.getSerialNumber().toString()));
        x509.put("X509IssuerSerial", one(keyIssuer));

        Map<String, Object> sigMethod = new LinkedHashMap<>();
        sigMethod.put("_", "");
        sigMethod.put("Algorithm", RSA_SHA256_URI);
        Map<String, Object> signedInfo = new LinkedHashMap<>();
        signedInfo.put("SignatureMethod", one(sigMethod));
        signedInfo.put("Reference", List.of(
                reference(SIGNED_PROPS_TYPE, "#" + SIGNED_PROPS_ID, propsDigest),
                reference("", "", docDigest)));

        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("Id", "signature");
        signature.put("Object", one(obj("QualifyingProperties", one(qualifying))));
        signature.put("KeyInfo", one(obj("X509Data", one(x509))));
        signature.put("SignatureValue", v(signatureValue));
        signature.put("SignedInfo", one(signedInfo));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("ID", v("urn:oasis:names:specification:ubl:signature:1"));
        info.put("ReferencedSignatureID", v(SIGNATURE_ID));
        info.put("Signature", one(signature));

        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("ExtensionURI", v(XADES_URI));
        ext.put("ExtensionContent", one(obj("UBLDocumentSignatures", one(obj("SignatureInformation", one(info))))));
        return one(obj("UBLExtension", one(ext)));
    }

    private static Map<String, Object> reference(String type, String uri, String digest) {
        Map<String, Object> dm = new LinkedHashMap<>();
        dm.put("_", "");
        dm.put("Algorithm", SHA256_URI);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("Type", type);
        r.put("URI", uri);
        r.put("DigestMethod", one(dm));
        r.put("DigestValue", v(digest));
        return r;
    }

    private static List<Object> signatureElement() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ID", v(SIGNATURE_ID));
        s.put("SignatureMethod", v(XADES_URI));
        return one(s);
    }

    /** Issuer distinguished name as LHDN shows it, e.g. "CN=Trial LHDNM Sub CA V1, OU=..., O=LHDNM, C=MY". */
    static String issuerName(X509Certificate cert) {
        return cert.getIssuerX500Principal().toString();
    }

    /** The document object inside the root ({@code Invoice}), whatever its name. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> document(Map<String, Object> root) {
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!e.getKey().startsWith("_") && e.getValue() instanceof List<?> l && !l.isEmpty()
                    && l.get(0) instanceof Map) {
                return (Map<String, Object>) l.get(0);
            }
        }
        throw new SigningException("Not a UBL-JSON document: no Invoice element", null);
    }

    static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /** Thrown when signing fails (bad key, unsupported algorithm, malformed document). */
    public static final class SigningException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SigningException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
