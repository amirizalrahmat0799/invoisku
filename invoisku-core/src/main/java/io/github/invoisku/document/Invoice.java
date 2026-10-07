package io.github.invoisku.document;

import io.github.invoisku.codes.DocumentType;
import io.github.invoisku.codes.PaymentMode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * An e-invoice of any of the eight MyInvois types, before it is converted to UBL.
 *
 * <p>Build one with {@link #builder(DocumentType)}. The amounts in the totals are not stored here; they are always
 * calculated from the lines by {@link io.github.invoisku.calc.Totals}, so they cannot disagree with them.
 *
 * <p>For self-billed types, {@code supplier} is still the party that sells (often a foreign supplier with
 * {@link io.github.invoisku.codes.GeneralTin#FOREIGN_SUPPLIER}) and {@code buyer} is you, the issuer.
 */
public record Invoice(
        DocumentType type,
        String version,
        String number,
        OffsetDateTime issuedAt,
        String currency,
        BigDecimal exchangeRate,
        InvoicePeriod period,
        Party supplier,
        Party buyer,
        Party shippingRecipient,
        List<LineItem> lines,
        List<AllowanceCharge> allowanceCharges,
        PaymentMode paymentMode,
        String supplierBankAccount,
        String paymentTerms,
        PrepaidPayment prepaid,
        BigDecimal roundingAmount,
        List<DocumentReference> originalInvoices,
        String billReference,
        String customsFormReference,
        String incoterms,
        String freeTradeAgreement) {

    /** Current document version, which requires a digital signature. */
    public static final String VERSION_1_1 = "1.1";
    /** Version without a signature; accepted by LHDN only for a transition period. */
    public static final String VERSION_1_0 = "1.0";

    public Invoice {
        lines = lines == null ? List.of() : List.copyOf(lines);
        allowanceCharges = allowanceCharges == null ? List.of() : List.copyOf(allowanceCharges);
        originalInvoices = originalInvoices == null ? List.of() : List.copyOf(originalInvoices);
    }

    public static Builder builder(DocumentType type) {
        return new Builder(type);
    }

    public static Builder invoice() {
        return new Builder(DocumentType.INVOICE);
    }

    public boolean isForeignCurrency() {
        return currency != null && !"MYR".equals(currency);
    }

    public static final class Builder {
        private final DocumentType type;
        private String version = VERSION_1_1;
        private String number;
        private OffsetDateTime issuedAt;
        private String currency = "MYR";
        private BigDecimal exchangeRate;
        private InvoicePeriod period;
        private Party supplier;
        private Party buyer;
        private Party shippingRecipient;
        private final List<LineItem> lines = new ArrayList<>();
        private final List<AllowanceCharge> allowanceCharges = new ArrayList<>();
        private PaymentMode paymentMode;
        private String supplierBankAccount;
        private String paymentTerms;
        private PrepaidPayment prepaid;
        private BigDecimal roundingAmount;
        private final List<DocumentReference> originalInvoices = new ArrayList<>();
        private String billReference;
        private String customsFormReference;
        private String incoterms;
        private String freeTradeAgreement;

        private Builder(DocumentType type) {
            this.type = type;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** Your invoice number (the e-invoice code number), up to 50 characters. */
        public Builder number(String number) {
            this.number = number;
            return this;
        }

        /** Issue date and time; LHDN expects it in UTC and within 72 hours before submission. */
        public Builder issuedAt(OffsetDateTime at) {
            this.issuedAt = at;
            return this;
        }

        public Builder currency(String iso4217) {
            this.currency = iso4217;
            return this;
        }

        /** Foreign currency: the currency code and the rate to MYR (1 unit = rate MYR). */
        public Builder currency(String iso4217, String rateToMyr) {
            this.currency = iso4217;
            this.exchangeRate = new BigDecimal(rateToMyr);
            return this;
        }

        public Builder period(InvoicePeriod period) {
            this.period = period;
            return this;
        }

        public Builder supplier(Party supplier) {
            this.supplier = supplier;
            return this;
        }

        public Builder buyer(Party buyer) {
            this.buyer = buyer;
            return this;
        }

        public Builder shippingRecipient(Party recipient) {
            this.shippingRecipient = recipient;
            return this;
        }

        public Builder line(LineItem line) {
            lines.add(line);
            return this;
        }

        public Builder lines(List<LineItem> items) {
            lines.addAll(items);
            return this;
        }

        public Builder allowanceCharge(AllowanceCharge ac) {
            allowanceCharges.add(ac);
            return this;
        }

        public Builder payment(PaymentMode mode, String supplierBankAccount) {
            this.paymentMode = mode;
            this.supplierBankAccount = supplierBankAccount;
            return this;
        }

        public Builder paymentMode(PaymentMode mode) {
            this.paymentMode = mode;
            return this;
        }

        public Builder paymentTerms(String terms) {
            this.paymentTerms = terms;
            return this;
        }

        public Builder prepaid(PrepaidPayment prepaid) {
            this.prepaid = prepaid;
            return this;
        }

        /** Cash rounding (for example to the nearest 5 sen); positive or negative. */
        public Builder rounding(String amount) {
            this.roundingAmount = new BigDecimal(amount);
            return this;
        }

        /** For credit, debit and refund notes: the e-invoice being adjusted. Repeatable. */
        public Builder adjusts(String originalUuid, String originalNumber) {
            originalInvoices.add(new DocumentReference(originalUuid, originalNumber));
            return this;
        }

        public Builder billReference(String ref) {
            this.billReference = ref;
            return this;
        }

        public Builder customsFormReference(String ref) {
            this.customsFormReference = ref;
            return this;
        }

        public Builder incoterms(String incoterms) {
            this.incoterms = incoterms;
            return this;
        }

        public Builder freeTradeAgreement(String fta) {
            this.freeTradeAgreement = fta;
            return this;
        }

        public Invoice build() {
            OffsetDateTime at = issuedAt != null
                    ? issuedAt
                    : OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
            List<LineItem> numbered = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                LineItem l = lines.get(i);
                numbered.add(l.id() != null ? l : new LineItem(String.valueOf(i + 1), l.classifications(),
                        l.tariffCode(), l.description(), l.quantity(), l.unitCode(), l.unitPrice(),
                        l.allowanceCharges(), l.taxes(), l.originCountry()));
            }
            return new Invoice(type, version, number, at, currency, exchangeRate, period, supplier, buyer,
                    shippingRecipient, numbered, allowanceCharges, paymentMode, supplierBankAccount, paymentTerms,
                    prepaid, roundingAmount, originalInvoices, billReference, customsFormReference, incoterms,
                    freeTradeAgreement);
        }
    }
}
