package io.github.invoisku.consolidate;

import io.github.invoisku.codes.Classification;
import io.github.invoisku.codes.DocumentType;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.InvoicePeriod;
import io.github.invoisku.document.LineItem;
import io.github.invoisku.document.LineTax;
import io.github.invoisku.document.Party;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the monthly consolidated e-invoice for sales to buyers who did not ask for an e-invoice (retail, F&amp;B,
 * e-commerce to consumers): buyer "General Public", classification 004, issued within 7 days after the month ends.
 *
 * <pre>{@code
 * Invoice inv = Consolidation.of(supplier, YearMonth.of(2026, 9))
 *         .receipt("R-0001", "12.50", LineTax.service("6"))
 *         .receipt("R-0002", "8.00", LineTax.service("6"))
 *         .build("CINV-2026-09");
 * }</pre>
 */
public final class Consolidation {

    /** How receipts become lines. */
    public enum Mode {
        /** One line per receipt; description is the receipt number. */
        PER_RECEIPT,
        /** One line per tax treatment, describing the receipt range ("R-0001 - R-0420"). */
        SUMMARY
    }

    public record Receipt(String number, BigDecimal amountExcludingTax, LineTax tax) {}

    private final Party supplier;
    private final YearMonth month;
    private final List<Receipt> receipts = new ArrayList<>();
    private Mode mode = Mode.SUMMARY;
    private OffsetDateTime issuedAt;

    private Consolidation(Party supplier, YearMonth month) {
        this.supplier = supplier;
        this.month = month;
    }

    public static Consolidation of(Party supplier, YearMonth month) {
        return new Consolidation(supplier, month);
    }

    /** Last day to submit the consolidated e-invoice for a month: the 7th of the following month. */
    public static LocalDate deadline(YearMonth month) {
        return month.plusMonths(1).atDay(7);
    }

    public Consolidation mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Consolidation issuedAt(OffsetDateTime at) {
        this.issuedAt = at;
        return this;
    }

    public Consolidation receipt(String number, String amountExcludingTax, LineTax tax) {
        receipts.add(new Receipt(number, new BigDecimal(amountExcludingTax), tax));
        return this;
    }

    public Consolidation receipts(List<Receipt> list) {
        receipts.addAll(list);
        return this;
    }

    public Invoice build(String invoiceNumber) {
        if (receipts.isEmpty()) throw new IllegalStateException("No receipts to consolidate");
        Invoice.Builder b = Invoice.builder(DocumentType.INVOICE)
                .number(invoiceNumber)
                .supplier(supplier)
                .buyer(Party.generalPublic())
                .period(new InvoicePeriod(month.atDay(1), month.atEndOfMonth(), "Monthly"));
        if (issuedAt != null) b.issuedAt(issuedAt);

        if (mode == Mode.PER_RECEIPT) {
            for (Receipt r : receipts) b.line(line(r.number(), r.amountExcludingTax(), r.tax()));
        } else {
            Map<LineTax, List<Receipt>> groups = new LinkedHashMap<>();
            for (Receipt r : receipts) groups.computeIfAbsent(r.tax(), k -> new ArrayList<>()).add(r);
            for (Map.Entry<LineTax, List<Receipt>> g : groups.entrySet()) {
                List<Receipt> rs = g.getValue();
                BigDecimal sum = BigDecimal.ZERO;
                for (Receipt r : rs) sum = sum.add(r.amountExcludingTax());
                String desc = rs.size() == 1 ? rs.get(0).number()
                        : rs.get(0).number() + " - " + rs.get(rs.size() - 1).number();
                b.line(line(desc, sum, g.getKey()));
            }
        }
        return b.build();
    }

    private static LineItem line(String description, BigDecimal amount, LineTax tax) {
        return LineItem.builder()
                .classification(Classification.CONSOLIDATED)
                .description(description)
                .quantity("1")
                .unitPrice(amount.toPlainString())
                .tax(tax == null ? LineTax.notApplicable() : tax)
                .build();
    }
}
