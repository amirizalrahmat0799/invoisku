package io.github.invoisku.document;

import io.github.invoisku.codes.GeneralTin;
import io.github.invoisku.codes.IdType;

/**
 * A supplier, buyer or shipping recipient.
 *
 * <p>{@code msicCode} and {@code businessActivity} are required for the supplier only. Use "NA" where LHDN allows
 * a value to be not applicable (SST and tourism tax registration numbers, email, and so on).
 */
public record Party(
        String tin,
        IdType idType,
        String idValue,
        String sstNumber,
        String tourismTaxNumber,
        String name,
        Address address,
        String phone,
        String email,
        String msicCode,
        String businessActivity) {

    public static Builder builder() {
        return new Builder();
    }

    /** The buyer of a consolidated e-invoice, exactly as LHDN prescribes it. */
    public static Party generalPublic() {
        return builder()
                .tin(GeneralTin.GENERAL_PUBLIC)
                .id(IdType.BRN, "NA")
                .sstNumber("NA")
                .name("General Public")
                .address(Address.notApplicable())
                .phone("NA")
                .email("NA")
                .build();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.tin = tin;
        b.idType = idType;
        b.idValue = idValue;
        b.sstNumber = sstNumber;
        b.tourismTaxNumber = tourismTaxNumber;
        b.name = name;
        b.address = address;
        b.phone = phone;
        b.email = email;
        b.msicCode = msicCode;
        b.businessActivity = businessActivity;
        return b;
    }

    public static final class Builder {
        private String tin;
        private IdType idType;
        private String idValue;
        private String sstNumber;
        private String tourismTaxNumber;
        private String name;
        private Address address;
        private String phone;
        private String email;
        private String msicCode;
        private String businessActivity;

        private Builder() {}

        public Builder tin(String tin) {
            this.tin = tin;
            return this;
        }

        public Builder id(IdType type, String value) {
            this.idType = type;
            this.idValue = value;
            return this;
        }

        public Builder brn(String brn) {
            return id(IdType.BRN, brn);
        }

        public Builder nric(String nric) {
            return id(IdType.NRIC, nric);
        }

        public Builder passport(String passport) {
            return id(IdType.PASSPORT, passport);
        }

        public Builder sstNumber(String sst) {
            this.sstNumber = sst;
            return this;
        }

        public Builder tourismTaxNumber(String ttx) {
            this.tourismTaxNumber = ttx;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder address(Address address) {
            this.address = address;
            return this;
        }

        public Builder phone(String phone) {
            this.phone = phone;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        /** Five-digit MSIC code and its description; supplier only. */
        public Builder msic(String code, String businessActivity) {
            this.msicCode = code;
            this.businessActivity = businessActivity;
            return this;
        }

        public Party build() {
            return new Party(tin, idType, idValue, sstNumber, tourismTaxNumber, name, address, phone, email,
                    msicCode, businessActivity);
        }
    }
}
