package io.github.invoisku.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.invoisku.TestData;
import io.github.invoisku.codes.DocumentType;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.LineItem;
import io.github.invoisku.document.LineTax;
import io.github.invoisku.document.Party;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ValidatorTest {

    private final Validator validator = new Validator(TestData.CLOCK);

    private List<String> errors(Invoice inv) {
        return validator.validate(inv).errors().stream().map(p -> p.path() + " " + p.code())
                .collect(Collectors.toList());
    }

    @Test
    void acceptsAGoodInvoice() {
        ValidationResult r = validator.validate(TestData.invoice().build());
        assertTrue(r.isValid(), r.toString());
        assertEquals(List.of(), r.warnings());
    }

    @Test
    void reportsEveryMissingFieldAtOnce() {
        Party empty = Party.builder().build();
        Invoice inv = Invoice.invoice().issuedAt(TestData.ISSUED).supplier(empty).buyer(empty).build();
        List<String> e = errors(inv);
        assertTrue(e.contains("number REQUIRED"), e.toString());
        assertTrue(e.contains("supplier.tin REQUIRED"), e.toString());
        assertTrue(e.contains("supplier.msicCode REQUIRED"), e.toString());
        assertTrue(e.contains("buyer.phone REQUIRED"), e.toString());
        assertTrue(e.contains("buyer.address REQUIRED"), e.toString());
        assertTrue(e.contains("lines REQUIRED"), e.toString());
        assertTrue(!e.contains("buyer.msicCode REQUIRED"), "buyer has no MSIC code");
    }

    @Test
    void checksLengthsAndFormats() {
        Party bad = TestData.supplier().toBuilder().name("x".repeat(301)).phone("call me").msic("5610", "x")
                .email("not-an-email").build();
        List<String> e = errors(TestData.invoice().supplier(bad).build());
        assertTrue(e.contains("supplier.name TOO_LONG"), e.toString());
        assertTrue(e.contains("supplier.phone FORMAT"), e.toString());
        assertTrue(e.contains("supplier.msicCode FORMAT"), e.toString());
        assertTrue(e.contains("supplier.email FORMAT"), e.toString());
    }

    @Test
    void exemptionNeedsAReason() {
        Invoice inv = TestData.invoice().line(LineItem.builder().description("Rice").quantity("1").unitPrice("3")
                .tax(LineTax.exempt(null)).build()).build();
        assertEquals(List.of("lines[2].taxes[0].exemptionReason REQUIRED"), errors(inv));
    }

    @Test
    void lineRules() {
        Invoice inv = TestData.invoice().line(LineItem.builder().classification("999").classification("036")
                .description("Bad").quantity("0").unitPrice("-1").quantity("0.123456", "piece").build()).build();
        List<String> e = errors(inv);
        assertTrue(e.contains("lines[2].classifications INVALID"), e.toString());
        assertTrue(e.contains("lines[2].classifications SELF_BILLED_ONLY"), e.toString());
        assertTrue(e.contains("lines[2].quantity PRECISION"), e.toString());
        assertTrue(e.contains("lines[2].unitPrice INVALID"), e.toString());
        assertTrue(e.contains("lines[2].unitCode FORMAT"), e.toString());
    }

    @Test
    void adjustmentsNeedTheOriginal() {
        Invoice base = TestData.invoice().build();
        Invoice note = Invoice.builder(DocumentType.CREDIT_NOTE).number("CN-1").issuedAt(TestData.ISSUED)
                .supplier(base.supplier()).buyer(base.buyer()).lines(base.lines()).build();
        assertEquals(List.of("originalInvoices REQUIRED"), errors(note));
    }

    @Test
    void foreignCurrencyNeedsARate() {
        assertEquals(List.of("exchangeRate REQUIRED"), errors(TestData.invoice().currency("USD").build()));
        assertEquals(List.of("currency INVALID"), errors(TestData.invoice().currency("XYZ", "1").build()));
    }

    @Test
    void issueTimeWindow() {
        assertEquals(List.of("issuedAt TOO_OLD"),
                errors(TestData.invoice().issuedAt(OffsetDateTime.parse("2026-09-27T00:00:00Z")).build()));
        assertEquals(List.of("issuedAt IN_FUTURE"),
                errors(TestData.invoice().issuedAt(OffsetDateTime.parse("2026-10-01T03:00:00Z")).build()));
    }

    @Test
    void negativeTotalsAreRejected() {
        Invoice inv = TestData.invoice().line(LineItem.builder().description("x").quantity("1").unitPrice("10")
                .discount("too much", "20").build()).build();
        assertEquals(List.of("lines[2] NEGATIVE"), errors(inv));
    }

    @Test
    void consolidatedLinesWarnAboutTheBuyer() {
        Invoice inv = TestData.invoice().line(LineItem.builder().classification("004").description("Receipts")
                .quantity("1").unitPrice("10").build()).build();
        ValidationResult r = validator.validate(inv);
        assertTrue(r.isValid());
        assertEquals("CONSOLIDATED_BUYER", r.warnings().get(0).code());
    }

    @Test
    void orThrowListsTheErrors() {
        ValidationResult.InvalidDocumentException ex = assertThrows(ValidationResult.InvalidDocumentException.class,
                () -> validator.validate(TestData.invoice().number(null).build()).orThrow());
        assertTrue(ex.getMessage().contains("number [REQUIRED]"), ex.getMessage());
    }
}
