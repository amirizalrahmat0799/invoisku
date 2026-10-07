package io.github.invoisku.document;

/**
 * The original e-invoice that a credit, debit or refund note adjusts: its LHDN UUID and your own invoice number.
 * For an original issued before e-invoicing applied, use uuid "NA".
 */
public record DocumentReference(String uuid, String internalId) {}
