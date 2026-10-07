package io.github.invoisku;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.invoisku.document.Invoice;
import io.github.invoisku.sign.DocumentSigner;
import io.github.invoisku.sign.PropsDigestScope;
import io.github.invoisku.sign.SignatureVerifier;
import io.github.invoisku.validate.ValidationResult;
import io.github.invoisku.validate.Validator;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EInvoicesTest {

    private final EInvoices einvoices = new EInvoices(
            new DocumentSigner(TestData.credentials(), TestData.CLOCK, PropsDigestScope.QUALIFYING_PROPERTIES),
            new Validator(TestData.CLOCK));

    @Test
    void preparesASignedDocument() {
        EInvoices.Prepared p = einvoices.prepare(TestData.invoice().build());
        assertTrue(SignatureVerifier.verify(p.json()).isValid());
        assertEquals(new BigDecimal("3179.90"), p.totals().payableAmount());
        assertEquals("INV-2026-0001", p.submittedDocument().codeNumber());
        assertEquals(64, p.submittedDocument().sha256Hex().length());
    }

    @Test
    void version10IsNotSigned() {
        EInvoices unsigned = new EInvoices(null, new Validator(TestData.CLOCK));
        EInvoices.Prepared p = unsigned.prepare(TestData.invoice().version(Invoice.VERSION_1_0).build());
        assertFalse(p.json().contains("UBLExtensions"));
        assertThrows(IllegalStateException.class, () -> unsigned.prepare(TestData.invoice().build()));
    }

    @Test
    void refusesInvalidDocuments() {
        assertThrows(ValidationResult.InvalidDocumentException.class,
                () -> einvoices.prepare(TestData.invoice().number("").build()));
    }
}
