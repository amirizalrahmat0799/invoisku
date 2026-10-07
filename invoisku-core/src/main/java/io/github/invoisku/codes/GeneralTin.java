package io.github.invoisku.codes;

/**
 * General TINs that LHDN publishes for parties without a Malaysian TIN.
 * Use them with registration number {@code "NA"} (or the passport number for foreigners, where known).
 */
public final class GeneralTin {
    private GeneralTin() {}

    /** Buyer in a consolidated e-invoice, or a buyer who does not ask for an e-invoice. */
    public static final String GENERAL_PUBLIC = "EI00000000010";
    /** Foreign buyer (exports). */
    public static final String FOREIGN_BUYER = "EI00000000020";
    /** Foreign supplier (self-billed e-invoice for imports). */
    public static final String FOREIGN_SUPPLIER = "EI00000000030";
    /** Buyer that is a government or statutory body. */
    public static final String GOVERNMENT = "EI00000000040";

    public static boolean isGeneral(String tin) {
        return tin != null && tin.matches("EI000000000[1-4]0");
    }
}
