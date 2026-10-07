package io.github.invoisku.codes;

/** MyInvois tax types (TaxCategory ID). */
public enum TaxType {
    SALES_TAX("01", "Sales Tax"),
    SERVICE_TAX("02", "Service Tax"),
    TOURISM_TAX("03", "Tourism Tax"),
    HIGH_VALUE_GOODS_TAX("04", "High-Value Goods Tax"),
    LOW_VALUE_GOODS_TAX("05", "Sales Tax on Low Value Goods"),
    NOT_APPLICABLE("06", "Not Applicable"),
    EXEMPT("E", "Tax exemption (where applicable)");

    private final String code;
    private final String label;

    TaxType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static TaxType ofCode(String code) {
        for (TaxType t : values()) if (t.code.equals(code)) return t;
        throw new IllegalArgumentException("Unknown tax type code: " + code);
    }
}
