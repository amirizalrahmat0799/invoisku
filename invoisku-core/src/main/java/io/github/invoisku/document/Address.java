package io.github.invoisku.document;

import io.github.invoisku.codes.State;
import java.util.List;
import java.util.Objects;

/**
 * A postal address. {@code country} is ISO 3166-1 alpha-3 ("MYS"). Up to three address lines, the first required.
 */
public record Address(List<String> lines, String postalZone, String city, State state, String country) {

    public Address {
        lines = lines == null ? List.of() : List.copyOf(lines);
        Objects.requireNonNull(state, "state");
    }

    /** A Malaysian address. */
    public static Address malaysia(String line1, String postcode, String city, State state) {
        return new Address(List.of(line1), postcode, city, state, "MYS");
    }

    /** A Malaysian address with two or three lines. */
    public static Address malaysia(List<String> lines, String postcode, String city, State state) {
        return new Address(lines, postcode, city, state, "MYS");
    }

    /** An address outside Malaysia (state "Not Applicable"). */
    public static Address foreign(List<String> lines, String postcode, String city, String countryAlpha3) {
        return new Address(lines, postcode, city, State.NOT_APPLICABLE, countryAlpha3);
    }

    /** "NA" placeholders, as LHDN asks for consolidated e-invoices to the general public. */
    public static Address notApplicable() {
        return new Address(List.of("NA"), "", "NA", State.NOT_APPLICABLE, "MYS");
    }
}
