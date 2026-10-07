package io.github.invoisku.sign;

/**
 * Which JSON is hashed for the XAdES SignedProperties digest. LHDN's JSON signing guide is open to both readings;
 * {@link SignatureVerifier} reports which one a signed document uses.
 */
public enum PropsDigestScope {
    /** {@code {"Target":"signature","SignedProperties":[...]}} (the QualifyingProperties content). */
    QUALIFYING_PROPERTIES,
    /** {@code {"Id":"id-xades-signed-props","SignedSignatureProperties":[...]}}. */
    SIGNED_PROPERTIES
}
