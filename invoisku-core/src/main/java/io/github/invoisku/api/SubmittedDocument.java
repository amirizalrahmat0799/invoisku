package io.github.invoisku.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * One document of a submission: the exact bytes to send, plus your invoice number.
 *
 * @param format     "JSON" or "XML"
 * @param codeNumber your invoice number (the document's ID); MyInvois echoes it back in the response
 */
public record SubmittedDocument(String format, String codeNumber, byte[] content) {

    /** Per-document limit. */
    public static final int MAX_BYTES = 300 * 1024;

    public static SubmittedDocument json(String codeNumber, String signedJson) {
        return new SubmittedDocument("JSON", codeNumber, signedJson.getBytes(StandardCharsets.UTF_8));
    }

    public static SubmittedDocument xml(String codeNumber, String signedXml) {
        return new SubmittedDocument("XML", codeNumber, signedXml.getBytes(StandardCharsets.UTF_8));
    }

    public String base64() {
        return Base64.getEncoder().encodeToString(content);
    }

    /** SHA-256 of the document bytes, hex. */
    public String sha256Hex() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bytes this document adds to the request body (base64 plus JSON overhead). */
    long encodedSize() {
        return 4L * ((content.length + 2) / 3) + 64 + 128 + codeNumber.length() + format.length();
    }
}
