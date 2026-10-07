package io.github.invoisku.codes;

/** MyInvois e-invoice types (InvoiceTypeCode). */
public enum DocumentType {
    INVOICE("01", "Invoice", false, false),
    CREDIT_NOTE("02", "Credit Note", false, true),
    DEBIT_NOTE("03", "Debit Note", false, true),
    REFUND_NOTE("04", "Refund Note", false, true),
    SELF_BILLED_INVOICE("11", "Self-billed Invoice", true, false),
    SELF_BILLED_CREDIT_NOTE("12", "Self-billed Credit Note", true, true),
    SELF_BILLED_DEBIT_NOTE("13", "Self-billed Debit Note", true, true),
    SELF_BILLED_REFUND_NOTE("14", "Self-billed Refund Note", true, true);

    private final String code;
    private final String label;
    private final boolean selfBilled;
    private final boolean adjustment;

    DocumentType(String code, String label, boolean selfBilled, boolean adjustment) {
        this.code = code;
        this.label = label;
        this.selfBilled = selfBilled;
        this.adjustment = adjustment;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** Issued by the buyer on the supplier's behalf. */
    public boolean selfBilled() {
        return selfBilled;
    }

    /** Credit, debit and refund notes, which must reference the original e-invoice. */
    public boolean adjustment() {
        return adjustment;
    }

    public static DocumentType ofCode(String code) {
        for (DocumentType t : values()) if (t.code.equals(code)) return t;
        throw new IllegalArgumentException("Unknown e-invoice type code: " + code);
    }
}
