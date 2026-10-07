package io.github.invoisku.document;

import io.github.invoisku.codes.TaxType;
import java.math.BigDecimal;

/**
 * One tax on a line: a percentage of the taxable amount, a fixed amount per unit, or an exemption.
 *
 * @param percent        rate in percent (6 for 6%), or null
 * @param perUnitAmount  fixed tax per unit of the line quantity (tourism tax, for example), or null
 * @param exemptionReason required for tax type {@link TaxType#EXEMPT}
 */
public record LineTax(TaxType type, BigDecimal percent, BigDecimal perUnitAmount, String exemptionReason) {

    public static LineTax percent(TaxType type, String percent) {
        return new LineTax(type, new BigDecimal(percent), null, null);
    }

    public static LineTax perUnit(TaxType type, String amountPerUnit) {
        return new LineTax(type, null, new BigDecimal(amountPerUnit), null);
    }

    public static LineTax exempt(String reason) {
        return new LineTax(TaxType.EXEMPT, BigDecimal.ZERO, null, reason);
    }

    public static LineTax notApplicable() {
        return new LineTax(TaxType.NOT_APPLICABLE, BigDecimal.ZERO, null, null);
    }

    /** Sales tax at the given percent (5 or 10 for most goods). */
    public static LineTax sales(String percent) {
        return percent(TaxType.SALES_TAX, percent);
    }

    /** Service tax at the given percent (6 or 8). */
    public static LineTax service(String percent) {
        return percent(TaxType.SERVICE_TAX, percent);
    }
}
