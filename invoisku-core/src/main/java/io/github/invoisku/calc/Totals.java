package io.github.invoisku.calc;

import io.github.invoisku.codes.TaxType;
import io.github.invoisku.document.AllowanceCharge;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.LineItem;
import io.github.invoisku.document.LineTax;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates every amount of an e-invoice from its lines, in {@link BigDecimal}, rounding half-up to 2 decimals
 * once per amount (never on intermediate products).
 *
 * <pre>
 * line subtotal        = quantity x unit price
 * line extension       = subtotal - line discounts + line charges
 * line tax (percent)   = line extension x percent / 100
 * line tax (per unit)  = quantity x amount per unit
 * line tax (exempt)    = 0
 *
 * LineExtensionAmount  = sum of line extensions
 * TaxExclusiveAmount   = LineExtensionAmount - document discounts + document charges
 * TaxAmount            = sum of line taxes
 * TaxInclusiveAmount   = TaxExclusiveAmount + TaxAmount
 * PayableAmount        = TaxInclusiveAmount - prepaid + rounding
 * </pre>
 *
 * <p>These are the UBL 2.1 relationships that LHDN's samples follow. Document-level discounts and charges are not
 * taxed separately: put taxable fees on a line instead.
 */
public final class Totals {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Totals() {}

    /** Rounds a monetary amount: 2 decimals, half up. */
    public static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    /** One tax on one line, or the sum of one tax type across the document. */
    public record TaxSubtotal(
            TaxType type, BigDecimal taxableAmount, BigDecimal taxAmount, BigDecimal percent,
            BigDecimal perUnitAmount, BigDecimal baseUnits, String exemptionReason) {}

    /** A discount or charge with its calculated amount. */
    public record AppliedAllowanceCharge(AllowanceCharge source, BigDecimal base, BigDecimal amount) {}

    public record LineTotals(
            LineItem line,
            BigDecimal subtotal,
            List<AppliedAllowanceCharge> allowanceCharges,
            BigDecimal lineExtension,
            List<TaxSubtotal> taxes,
            BigDecimal taxAmount) {}

    public record DocumentTotals(
            List<LineTotals> lines,
            List<AppliedAllowanceCharge> allowanceCharges,
            BigDecimal lineExtensionAmount,
            BigDecimal allowanceTotalAmount,
            BigDecimal chargeTotalAmount,
            BigDecimal taxExclusiveAmount,
            List<TaxSubtotal> taxSubtotals,
            BigDecimal taxAmount,
            BigDecimal taxInclusiveAmount,
            BigDecimal prepaidAmount,
            BigDecimal payableRoundingAmount,
            BigDecimal payableAmount) {

        /** Tax total in MYR, for foreign-currency invoices (the tax amount times the exchange rate). */
        public BigDecimal taxAmountInMyr(BigDecimal exchangeRate) {
            return exchangeRate == null ? taxAmount : money(taxAmount.multiply(exchangeRate));
        }
    }

    public static LineTotals line(LineItem line) {
        BigDecimal qty = nz(line.quantity());
        BigDecimal subtotal = money(qty.multiply(nz(line.unitPrice())));
        List<AppliedAllowanceCharge> applied = apply(line.allowanceCharges(), subtotal);
        BigDecimal extension = subtotal;
        for (AppliedAllowanceCharge a : applied) {
            extension = a.source().charge() ? extension.add(a.amount()) : extension.subtract(a.amount());
        }
        List<TaxSubtotal> taxes = new ArrayList<>();
        BigDecimal taxTotal = BigDecimal.ZERO.setScale(2);
        for (LineTax t : line.taxes()) {
            BigDecimal amount;
            if (t.type() == TaxType.EXEMPT || t.type() == TaxType.NOT_APPLICABLE) {
                amount = BigDecimal.ZERO.setScale(2);
            } else if (t.perUnitAmount() != null) {
                amount = money(qty.multiply(t.perUnitAmount()));
            } else {
                amount = money(extension.multiply(nz(t.percent())).divide(HUNDRED));
            }
            taxes.add(new TaxSubtotal(t.type(), extension, amount, t.perUnitAmount() == null ? nz(t.percent()) : null,
                    t.perUnitAmount(), t.perUnitAmount() == null ? null : qty, t.exemptionReason()));
            taxTotal = taxTotal.add(amount);
        }
        return new LineTotals(line, subtotal, applied, extension, taxes, taxTotal);
    }

    public static DocumentTotals of(Invoice invoice) {
        List<LineTotals> lines = new ArrayList<>();
        BigDecimal lineExtension = BigDecimal.ZERO.setScale(2);
        BigDecimal tax = BigDecimal.ZERO.setScale(2);
        Map<TaxType, BigDecimal[]> byType = new LinkedHashMap<>();
        for (LineItem item : invoice.lines()) {
            LineTotals lt = line(item);
            lines.add(lt);
            lineExtension = lineExtension.add(lt.lineExtension());
            tax = tax.add(lt.taxAmount());
            for (TaxSubtotal ts : lt.taxes()) {
                BigDecimal[] sums = byType.computeIfAbsent(ts.type(),
                        k -> new BigDecimal[] {BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)});
                sums[0] = sums[0].add(ts.taxableAmount());
                sums[1] = sums[1].add(ts.taxAmount());
            }
        }
        List<TaxSubtotal> subtotals = new ArrayList<>();
        byType.forEach((type, sums) -> subtotals.add(new TaxSubtotal(type, sums[0], sums[1], null, null, null, null)));

        List<AppliedAllowanceCharge> docAc = apply(invoice.allowanceCharges(), lineExtension);
        BigDecimal allowances = BigDecimal.ZERO.setScale(2);
        BigDecimal charges = BigDecimal.ZERO.setScale(2);
        for (AppliedAllowanceCharge a : docAc) {
            if (a.source().charge()) charges = charges.add(a.amount());
            else allowances = allowances.add(a.amount());
        }
        BigDecimal taxExclusive = lineExtension.subtract(allowances).add(charges);
        BigDecimal taxInclusive = taxExclusive.add(tax);
        BigDecimal prepaid = invoice.prepaid() == null ? BigDecimal.ZERO.setScale(2) : money(invoice.prepaid().amount());
        BigDecimal rounding = invoice.roundingAmount() == null ? BigDecimal.ZERO.setScale(2)
                : money(invoice.roundingAmount());
        BigDecimal payable = taxInclusive.subtract(prepaid).add(rounding);
        return new DocumentTotals(lines, docAc, lineExtension, allowances, charges, taxExclusive, subtotals, tax,
                taxInclusive, prepaid, rounding, payable);
    }

    /**
     * The rounding adjustment that brings an amount to the nearest {@code step} (0.05 for Malaysian cash rounding).
     */
    public static BigDecimal cashRounding(BigDecimal amount, BigDecimal step) {
        BigDecimal rounded = amount.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
        return money(rounded.subtract(amount));
    }

    private static List<AppliedAllowanceCharge> apply(List<AllowanceCharge> list, BigDecimal base) {
        List<AppliedAllowanceCharge> out = new ArrayList<>(list.size());
        for (AllowanceCharge ac : list) {
            BigDecimal amount = ac.amount() != null ? money(ac.amount()) : money(base.multiply(nz(ac.rate())));
            out.add(new AppliedAllowanceCharge(ac, base, amount));
        }
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
