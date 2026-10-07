package io.github.invoisku.document;

import io.github.invoisku.codes.Classification;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A line of an e-invoice.
 *
 * @param classifications LHDN classification codes ("022" others, "003" computers, ...); at least one
 * @param tariffCode      customs tariff code (PTC), for imports and exports; optional
 * @param unitCode        UN/ECE recommendation 20 unit ("C62" one, "H87" piece, "KGM" kilogram); optional
 */
public record LineItem(
        String id,
        List<String> classifications,
        String tariffCode,
        String description,
        BigDecimal quantity,
        String unitCode,
        BigDecimal unitPrice,
        List<AllowanceCharge> allowanceCharges,
        List<LineTax> taxes,
        String originCountry) {

    public LineItem {
        classifications = classifications == null ? List.of() : List.copyOf(classifications);
        allowanceCharges = allowanceCharges == null ? List.of() : List.copyOf(allowanceCharges);
        taxes = taxes == null ? List.of() : List.copyOf(taxes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private final List<String> classifications = new ArrayList<>();
        private String tariffCode;
        private String description;
        private BigDecimal quantity = BigDecimal.ONE;
        private String unitCode;
        private BigDecimal unitPrice;
        private final List<AllowanceCharge> allowanceCharges = new ArrayList<>();
        private final List<LineTax> taxes = new ArrayList<>();
        private String originCountry;

        private Builder() {}

        /** Line ID; defaults to the line's position (1, 2, 3...). */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder classification(String code) {
            classifications.add(code);
            return this;
        }

        public Builder tariffCode(String ptc) {
            this.tariffCode = ptc;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder quantity(String quantity) {
            this.quantity = new BigDecimal(quantity);
            return this;
        }

        public Builder quantity(String quantity, String unitCode) {
            this.unitCode = unitCode;
            return quantity(quantity);
        }

        public Builder unitPrice(String price) {
            this.unitPrice = new BigDecimal(price);
            return this;
        }

        public Builder discount(String reason, String amount) {
            allowanceCharges.add(AllowanceCharge.discount(reason, amount));
            return this;
        }

        public Builder allowanceCharge(AllowanceCharge ac) {
            allowanceCharges.add(ac);
            return this;
        }

        public Builder tax(LineTax tax) {
            taxes.add(tax);
            return this;
        }

        public Builder originCountry(String alpha3) {
            this.originCountry = alpha3;
            return this;
        }

        public LineItem build() {
            List<String> classes = classifications.isEmpty() ? List.of(Classification.OTHERS) : classifications;
            List<LineTax> t = taxes.isEmpty() ? List.of(LineTax.notApplicable()) : taxes;
            return new LineItem(id, classes, tariffCode, description, quantity, unitCode, unitPrice,
                    allowanceCharges, t, originCountry);
        }
    }
}
