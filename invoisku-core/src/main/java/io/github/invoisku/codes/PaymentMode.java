package io.github.invoisku.codes;

/** Payment modes (PaymentMeansCode). */
public enum PaymentMode {
    CASH("01", "Cash"),
    CHEQUE("02", "Cheque"),
    BANK_TRANSFER("03", "Bank Transfer"),
    CREDIT_CARD("04", "Credit Card"),
    DEBIT_CARD("05", "Debit Card"),
    E_WALLET("06", "e-Wallet / Digital Wallet"),
    DIGITAL_BANK("07", "Digital Bank"),
    OTHERS("08", "Others");

    private final String code;
    private final String label;

    PaymentMode(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static PaymentMode ofCode(String code) {
        for (PaymentMode p : values()) if (p.code.equals(code)) return p;
        throw new IllegalArgumentException("Unknown payment mode code: " + code);
    }
}
