package io.github.invoisku.validate;

import io.github.invoisku.calc.Totals;
import io.github.invoisku.calc.Totals.DocumentTotals;
import io.github.invoisku.codes.Classification;
import io.github.invoisku.codes.GeneralTin;
import io.github.invoisku.codes.State;
import io.github.invoisku.codes.TaxType;
import io.github.invoisku.document.Address;
import io.github.invoisku.document.AllowanceCharge;
import io.github.invoisku.document.DocumentReference;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.LineItem;
import io.github.invoisku.document.LineTax;
import io.github.invoisku.document.Party;
import io.github.invoisku.validate.Problem.Severity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks an {@link Invoice} against MyInvois rules before it is sent: mandatory fields, maximum lengths, code
 * tables, formats, the references that credit, debit and refund notes need, and the 72-hour issue window.
 *
 * <p>It reports every problem at once rather than stopping at the first, so a user can fix a form in one go.
 * It is deliberately conservative: an {@link Severity#ERROR} is something LHDN rejects; a
 * {@link Severity#WARNING} is unusual but accepted.
 */
public final class Validator {

    private static final Pattern MSIC = Pattern.compile("\\d{5}");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 -]{6,18}[0-9]");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern TIN = Pattern.compile("(IG\\d{9,11}|[A-Z]{1,2}\\d{8,11}0?|EI\\d{11})");
    private static final Pattern UNIT = Pattern.compile("[A-Z0-9]{2,3}");
    private static final Set<String> COUNTRIES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA3);
    private static final Duration ISSUE_WINDOW = Duration.ofHours(72);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    public Validator() {
        this(Clock.systemUTC());
    }

    /** With a fixed clock, for tests or for re-validating old documents. */
    public Validator(Clock clock) {
        this.clock = clock;
    }

    public ValidationResult validate(Invoice inv) {
        Ctx c = new Ctx();
        c.required("number", inv.number(), 50);
        if (!Invoice.VERSION_1_0.equals(inv.version()) && !Invoice.VERSION_1_1.equals(inv.version())) {
            c.error("version", "INVALID", "Version must be 1.0 or 1.1, not " + inv.version());
        }
        checkIssueTime(c, inv.issuedAt());
        checkCurrency(c, inv);

        if (inv.supplier() == null) c.error("supplier", "REQUIRED", "Supplier is required");
        else party(c, "supplier", inv.supplier(), true);
        if (inv.buyer() == null) c.error("buyer", "REQUIRED", "Buyer is required");
        else party(c, "buyer", inv.buyer(), false);
        if (inv.shippingRecipient() != null) {
            Party s = inv.shippingRecipient();
            c.optional("shippingRecipient.name", s.name(), 300);
            if (s.address() != null) address(c, "shippingRecipient.address", s.address());
        }

        if (inv.type().adjustment()) {
            if (inv.originalInvoices().isEmpty()) {
                c.error("originalInvoices", "REQUIRED",
                        inv.type().label() + " must reference the original e-invoice (adjusts(uuid, number))");
            }
            for (int i = 0; i < inv.originalInvoices().size(); i++) {
                DocumentReference r = inv.originalInvoices().get(i);
                c.required("originalInvoices[" + i + "].uuid", r.uuid(), 100);
                c.required("originalInvoices[" + i + "].internalId", r.internalId(), 100);
            }
        } else if (!inv.originalInvoices().isEmpty()) {
            c.warn("originalInvoices", "UNEXPECTED", "Only credit, debit and refund notes reference an original");
        }

        if (inv.lines().isEmpty()) c.error("lines", "REQUIRED", "At least one line is required");
        boolean consolidated = false;
        for (int i = 0; i < inv.lines().size(); i++) {
            consolidated |= line(c, "lines[" + i + "]", inv.lines().get(i), inv);
        }
        if (consolidated && inv.buyer() != null && !GeneralTin.GENERAL_PUBLIC.equals(inv.buyer().tin())) {
            c.warn("buyer.tin", "CONSOLIDATED_BUYER",
                    "A consolidated e-invoice (classification 004) normally uses the general public TIN "
                            + GeneralTin.GENERAL_PUBLIC);
        }

        for (int i = 0; i < inv.allowanceCharges().size(); i++) {
            allowanceCharge(c, "allowanceCharges[" + i + "]", inv.allowanceCharges().get(i));
        }
        c.optional("supplierBankAccount", inv.supplierBankAccount(), 150);
        c.optional("paymentTerms", inv.paymentTerms(), 300);
        c.optional("billReference", inv.billReference(), 150);
        c.optional("customsFormReference", inv.customsFormReference(), 12);
        c.optional("incoterms", inv.incoterms(), 3);
        c.optional("freeTradeAgreement", inv.freeTradeAgreement(), 300);
        if (inv.prepaid() != null) {
            if (inv.prepaid().amount() == null || inv.prepaid().amount().signum() < 0) {
                c.error("prepaid.amount", "INVALID", "Prepaid amount must be zero or more");
            }
            c.optional("prepaid.reference", inv.prepaid().reference(), 150);
        }
        if (inv.period() != null && inv.period().start() != null && inv.period().end() != null
                && inv.period().end().isBefore(inv.period().start())) {
            c.error("period", "INVALID", "Billing period ends before it starts");
        }

        if (c.errors == 0) {
            DocumentTotals t = Totals.of(inv);
            if (t.payableAmount().signum() < 0) {
                c.error("totals.payableAmount", "NEGATIVE",
                        "Payable amount is negative (" + t.payableAmount() + "); issue a credit note instead");
            }
            for (int i = 0; i < t.lines().size(); i++) {
                if (t.lines().get(i).lineExtension().signum() < 0) {
                    c.error("lines[" + i + "]", "NEGATIVE", "Line amount after discounts is negative");
                }
            }
        }
        return new ValidationResult(c.problems);
    }

    private void checkIssueTime(Ctx c, OffsetDateTime at) {
        if (at == null) {
            c.error("issuedAt", "REQUIRED", "Issue date and time are required");
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (at.isAfter(now.plus(CLOCK_SKEW))) {
            c.error("issuedAt", "IN_FUTURE", "Issue time " + at + " is in the future");
        } else if (at.isBefore(now.minus(ISSUE_WINDOW))) {
            c.error("issuedAt", "TOO_OLD", "LHDN only accepts documents issued within the last 72 hours");
        }
    }

    private static void checkCurrency(Ctx c, Invoice inv) {
        String cur = inv.currency();
        if (cur == null || cur.isEmpty()) {
            c.error("currency", "REQUIRED", "Currency is required");
            return;
        }
        try {
            Currency.getInstance(cur);
        } catch (IllegalArgumentException e) {
            c.error("currency", "INVALID", "Not an ISO 4217 currency code: " + cur);
        }
        if (inv.isForeignCurrency()) {
            if (inv.exchangeRate() == null || inv.exchangeRate().signum() <= 0) {
                c.error("exchangeRate", "REQUIRED", "An exchange rate to MYR is required for " + cur);
            }
        } else if (inv.exchangeRate() != null && inv.exchangeRate().compareTo(BigDecimal.ONE) != 0) {
            c.warn("exchangeRate", "UNEXPECTED", "Exchange rate is ignored for MYR documents");
        }
    }

    private static void party(Ctx c, String path, Party p, boolean supplier) {
        if (c.required(path + ".tin", p.tin(), 14) && !TIN.matcher(p.tin()).matches()) {
            c.warn(path + ".tin", "FORMAT", "\"" + p.tin() + "\" does not look like a Malaysian TIN");
        }
        if (p.idType() == null) {
            c.error(path + ".idType", "REQUIRED", "Registration type is required (BRN, NRIC, PASSPORT or ARMY)");
        } else {
            c.required(path + ".idValue", p.idValue(), 20);
        }
        c.required(path + ".name", p.name(), 300);
        if (p.sstNumber() != null) {
            c.optional(path + ".sstNumber", p.sstNumber(), 35);
            if (p.sstNumber().split(";").length > 2) {
                c.error(path + ".sstNumber", "INVALID", "At most two SST registration numbers, separated by ';'");
            }
        }
        c.optional(path + ".tourismTaxNumber", p.tourismTaxNumber(), 17);
        if (p.address() == null) c.error(path + ".address", "REQUIRED", "Address is required");
        else address(c, path + ".address", p.address());
        if (c.required(path + ".phone", p.phone(), 20) && !"NA".equals(p.phone())
                && !PHONE.matcher(p.phone()).matches()) {
            c.error(path + ".phone", "FORMAT", "Not a phone number: " + p.phone());
        }
        if (p.email() != null && !"NA".equals(p.email())) {
            c.optional(path + ".email", p.email(), 320);
            if (!EMAIL.matcher(p.email()).matches()) c.error(path + ".email", "FORMAT", "Not an email: " + p.email());
        }
        if (supplier) {
            if (c.required(path + ".msicCode", p.msicCode(), 5) && !MSIC.matcher(p.msicCode()).matches()) {
                c.error(path + ".msicCode", "FORMAT", "MSIC code must be 5 digits (00000 if not applicable)");
            }
            c.required(path + ".businessActivity", p.businessActivity(), 300);
        }
    }

    private static void address(Ctx c, String path, Address a) {
        if (a.lines().isEmpty()) c.error(path + ".lines", "REQUIRED", "At least one address line is required");
        if (a.lines().size() > 3) c.error(path + ".lines", "TOO_MANY", "At most three address lines");
        for (int i = 0; i < a.lines().size(); i++) c.required(path + ".lines[" + i + "]", a.lines().get(i), 150);
        c.required(path + ".city", a.city(), 50);
        c.optional(path + ".postalZone", a.postalZone(), 50);
        if (c.required(path + ".country", a.country(), 3) && !COUNTRIES.contains(a.country())) {
            c.error(path + ".country", "INVALID", "Not an ISO 3166-1 alpha-3 country code: " + a.country());
        }
        if ("MYS".equals(a.country()) && a.state() == State.NOT_APPLICABLE && !"NA".equals(a.city())) {
            c.warn(path + ".state", "STATE", "Malaysian address without a state");
        }
        if (a.country() != null && !"MYS".equals(a.country()) && a.state() != State.NOT_APPLICABLE) {
            c.warn(path + ".state", "STATE", "Foreign addresses use state 17 (Not Applicable)");
        }
    }

    /** Returns true if the line is a consolidated line (classification 004). */
    private static boolean line(Ctx c, String path, LineItem l, Invoice inv) {
        c.required(path + ".description", l.description(), 300);
        c.required(path + ".id", l.id(), 50);
        if (l.classifications().isEmpty()) c.error(path + ".classifications", "REQUIRED", "Classification required");
        boolean consolidated = false;
        for (String code : l.classifications()) {
            if (!Classification.isValid(code)) {
                c.error(path + ".classifications", "INVALID", "Unknown classification code: " + code);
            } else if (Classification.isSelfBilledOnly(code) && !inv.type().selfBilled()) {
                c.error(path + ".classifications", "SELF_BILLED_ONLY",
                        "Classification " + code + " is only for self-billed e-invoices");
            }
            consolidated |= Classification.CONSOLIDATED.equals(code);
        }
        c.optional(path + ".tariffCode", l.tariffCode(), 12);
        if (l.quantity() == null || l.quantity().signum() <= 0) {
            c.error(path + ".quantity", "INVALID", "Quantity must be more than zero");
        } else if (l.quantity().stripTrailingZeros().scale() > 5) {
            c.error(path + ".quantity", "PRECISION", "Quantity has more than 5 decimals");
        }
        if (l.unitPrice() == null) c.error(path + ".unitPrice", "REQUIRED", "Unit price is required");
        else if (l.unitPrice().signum() < 0) c.error(path + ".unitPrice", "INVALID", "Unit price cannot be negative");
        if (l.unitCode() != null && !UNIT.matcher(l.unitCode()).matches()) {
            c.error(path + ".unitCode", "FORMAT", "Unit code must be a UN/ECE rec. 20 code, like C62 or KGM");
        }
        if (l.originCountry() != null && !COUNTRIES.contains(l.originCountry())) {
            c.error(path + ".originCountry", "INVALID", "Not an ISO 3166-1 alpha-3 country code");
        }
        if (l.taxes().isEmpty()) c.error(path + ".taxes", "REQUIRED", "At least one tax (or Not Applicable)");
        for (int i = 0; i < l.taxes().size(); i++) {
            LineTax t = l.taxes().get(i);
            String tp = path + ".taxes[" + i + "]";
            if (t.type() == null) {
                c.error(tp, "REQUIRED", "Tax type is required");
                continue;
            }
            if (t.type() == TaxType.EXEMPT) {
                c.required(tp + ".exemptionReason", t.exemptionReason(), 300);
            } else if (t.exemptionReason() != null) {
                c.warn(tp + ".exemptionReason", "UNEXPECTED", "Exemption reason is only used with tax type E");
            }
            if (t.percent() != null && t.perUnitAmount() != null) {
                c.error(tp, "AMBIGUOUS", "Give a percent or a per-unit amount, not both");
            }
            if (t.percent() != null && (t.percent().signum() < 0 || t.percent().compareTo(BigDecimal.valueOf(100)) > 0)) {
                c.error(tp + ".percent", "INVALID", "Tax percent must be between 0 and 100");
            }
            if (t.perUnitAmount() != null && t.perUnitAmount().signum() < 0) {
                c.error(tp + ".perUnitAmount", "INVALID", "Per-unit tax cannot be negative");
            }
            boolean chargeable = t.type() != TaxType.EXEMPT && t.type() != TaxType.NOT_APPLICABLE;
            if (chargeable && t.perUnitAmount() == null && (t.percent() == null || t.percent().signum() == 0)) {
                c.warn(tp, "ZERO_RATE", "Tax type " + t.type().code() + " with no rate; did you mean 06 or E?");
            }
        }
        for (int i = 0; i < l.allowanceCharges().size(); i++) {
            allowanceCharge(c, path + ".allowanceCharges[" + i + "]", l.allowanceCharges().get(i));
        }
        return consolidated;
    }

    private static void allowanceCharge(Ctx c, String path, AllowanceCharge ac) {
        c.optional(path + ".reason", ac.reason(), 300);
        if (ac.amount() == null && ac.rate() == null) c.error(path, "REQUIRED", "Give an amount or a rate");
        if (ac.amount() != null && ac.amount().signum() < 0) c.error(path + ".amount", "INVALID", "Negative amount");
        if (ac.rate() != null && (ac.rate().signum() < 0 || ac.rate().compareTo(BigDecimal.ONE) > 0)) {
            c.error(path + ".rate", "INVALID", "Rate is a fraction between 0 and 1 (0.10 for 10%)");
        }
    }

    private static final class Ctx {
        final List<Problem> problems = new ArrayList<>();
        int errors;

        void error(String path, String code, String message) {
            problems.add(new Problem(Severity.ERROR, path, code, message));
            errors++;
        }

        void warn(String path, String code, String message) {
            problems.add(new Problem(Severity.WARNING, path, code, message));
        }

        /** Returns true if present and short enough. */
        boolean required(String path, String value, int max) {
            if (value == null || value.isBlank()) {
                error(path, "REQUIRED", "Required");
                return false;
            }
            return optional(path, value, max);
        }

        boolean optional(String path, String value, int max) {
            if (value != null && value.length() > max) {
                error(path, "TOO_LONG", "At most " + max + " characters (has " + value.length() + ")");
                return false;
            }
            return true;
        }
    }
}
