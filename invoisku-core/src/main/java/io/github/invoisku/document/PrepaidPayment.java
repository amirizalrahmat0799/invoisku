package io.github.invoisku.document;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A payment received before the e-invoice: deducted from the payable amount. */
public record PrepaidPayment(String reference, BigDecimal amount, OffsetDateTime paidAt) {}
