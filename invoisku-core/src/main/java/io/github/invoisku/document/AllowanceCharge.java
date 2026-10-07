package io.github.invoisku.document;

import java.math.BigDecimal;

/**
 * A discount (allowance) or a fee (charge), on a line or on the whole document.
 *
 * <p>Give either a fixed {@code amount}, or a {@code rate} as a fraction of the base (0.10 for 10%); with a rate the
 * amount is calculated, rounded half-up to 2 decimals.
 */
public record AllowanceCharge(boolean charge, String reason, BigDecimal amount, BigDecimal rate) {

    public static AllowanceCharge discount(String reason, String amount) {
        return new AllowanceCharge(false, reason, new BigDecimal(amount), null);
    }

    public static AllowanceCharge discountRate(String reason, String fraction) {
        return new AllowanceCharge(false, reason, null, new BigDecimal(fraction));
    }

    public static AllowanceCharge charge(String reason, String amount) {
        return new AllowanceCharge(true, reason, new BigDecimal(amount), null);
    }

    public static AllowanceCharge chargeRate(String reason, String fraction) {
        return new AllowanceCharge(true, reason, null, new BigDecimal(fraction));
    }
}
