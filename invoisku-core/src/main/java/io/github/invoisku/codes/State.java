package io.github.invoisku.codes;

/** Malaysian state codes (CountrySubentityCode). */
public enum State {
    JOHOR("01", "Johor"),
    KEDAH("02", "Kedah"),
    KELANTAN("03", "Kelantan"),
    MELAKA("04", "Melaka"),
    NEGERI_SEMBILAN("05", "Negeri Sembilan"),
    PAHANG("06", "Pahang"),
    PULAU_PINANG("07", "Pulau Pinang"),
    PERAK("08", "Perak"),
    PERLIS("09", "Perlis"),
    SELANGOR("10", "Selangor"),
    TERENGGANU("11", "Terengganu"),
    SABAH("12", "Sabah"),
    SARAWAK("13", "Sarawak"),
    KUALA_LUMPUR("14", "Wilayah Persekutuan Kuala Lumpur"),
    LABUAN("15", "Wilayah Persekutuan Labuan"),
    PUTRAJAYA("16", "Wilayah Persekutuan Putrajaya"),
    NOT_APPLICABLE("17", "Not Applicable");

    private final String code;
    private final String label;

    State(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static State ofCode(String code) {
        for (State s : values()) if (s.code.equals(code)) return s;
        throw new IllegalArgumentException("Unknown state code: " + code);
    }
}
