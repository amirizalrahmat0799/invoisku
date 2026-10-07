package io.github.invoisku.codes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Product / service classification codes (ItemClassificationCode with listID "CLASS"). */
public final class Classification {
    private Classification() {}

    public static final String CONSOLIDATED = "004";
    public static final String OTHERS = "022";

    private static final Map<String, String> CODES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("001", "Breastfeeding equipment");
        m.put("002", "Child care centres and kindergartens fees");
        m.put("003", "Computer, smartphone or tablet");
        m.put("004", "Consolidated e-Invoice");
        m.put("005", "Construction materials");
        m.put("006", "Disbursement");
        m.put("007", "Donation");
        m.put("008", "e-Commerce - e-Invoice to buyer / purchaser");
        m.put("009", "e-Commerce - Self-billed e-Invoice to seller, logistics, etc.");
        m.put("010", "Education fees");
        m.put("011", "Goods on consignment (Consignor)");
        m.put("012", "Goods on consignment (Consignee)");
        m.put("013", "Gym membership");
        m.put("014", "Insurance - Education and medical benefits");
        m.put("015", "Insurance - Takaful or life insurance");
        m.put("016", "Interest and financing expenses");
        m.put("017", "Internet subscription");
        m.put("018", "Land and building");
        m.put("019", "Medical examination for learning disabilities and early intervention or rehabilitation");
        m.put("020", "Medical examination or vaccination expenses");
        m.put("021", "Medical expenses for serious diseases");
        m.put("022", "Others");
        m.put("023", "Petroleum operations");
        m.put("024", "Private retirement scheme or deferred annuity scheme");
        m.put("025", "Motor vehicle");
        m.put("026", "Subscription of books / journals / magazines / newspapers / other similar publications");
        m.put("027", "Reimbursement");
        m.put("028", "Rental of motor vehicle");
        m.put("029", "EV charging facilities");
        m.put("030", "Repair and maintenance");
        m.put("031", "Research and development");
        m.put("032", "Foreign income");
        m.put("033", "Self-billed - Betting and gaming");
        m.put("034", "Self-billed - Importation of goods");
        m.put("035", "Self-billed - Importation of services");
        m.put("036", "Self-billed - Others");
        m.put("037", "Self-billed - Monetary payment to agents, dealers or distributors");
        m.put("038", "Sports equipment, sports facilities, competitions or training");
        m.put("039", "Supporting equipment for disabled person");
        m.put("040", "Voluntary contribution to approved provident fund");
        m.put("041", "Dental examination or treatment");
        m.put("042", "Fertility treatment");
        m.put("043", "Treatment and home care nursing, daycare centres and residential care centres");
        m.put("044", "Vouchers, gift cards, loyalty points, etc");
        m.put("045", "Self-billed - Non-monetary payment to agents, dealers or distributors");
        CODES = Collections.unmodifiableMap(m);
    }

    public static boolean isValid(String code) {
        return CODES.containsKey(code);
    }

    public static String describe(String code) {
        return CODES.get(code);
    }

    /** Codes reserved for self-billed e-invoices. */
    public static boolean isSelfBilledOnly(String code) {
        return code != null && (code.compareTo("033") >= 0 && code.compareTo("037") <= 0 || code.equals("045"));
    }

    public static Map<String, String> all() {
        return CODES;
    }
}
