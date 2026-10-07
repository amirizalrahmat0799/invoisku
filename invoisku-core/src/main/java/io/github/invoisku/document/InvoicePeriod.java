package io.github.invoisku.document;

import java.time.LocalDate;

/** Billing period, for recurring invoices and consolidated e-invoices. Frequency: "Monthly", "Daily", ... */
public record InvoicePeriod(LocalDate start, LocalDate end, String frequency) {}
