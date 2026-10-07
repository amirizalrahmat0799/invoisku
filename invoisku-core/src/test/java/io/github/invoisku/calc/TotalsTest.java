package io.github.invoisku.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.invoisku.TestData;
import io.github.invoisku.codes.TaxType;
import io.github.invoisku.document.AllowanceCharge;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.LineItem;
import io.github.invoisku.document.LineTax;
import io.github.invoisku.document.PrepaidPayment;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TotalsTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void calculatesLinesAndDocument() {
        Totals.DocumentTotals t = Totals.of(TestData.invoice().build());
        Totals.LineTotals laptop = t.lines().get(0);
        assertEquals(d("2873.00"), laptop.subtotal());
        assertEquals(d("2773.00"), laptop.lineExtension());
        assertEquals(d("277.30"), laptop.taxAmount());
        assertEquals(d("9.60"), t.lines().get(1).taxAmount());

        assertEquals(d("2893.00"), t.lineExtensionAmount());
        assertEquals(d("2893.00"), t.taxExclusiveAmount());
        assertEquals(d("286.90"), t.taxAmount());
        assertEquals(d("3179.90"), t.taxInclusiveAmount());
        assertEquals(d("3179.90"), t.payableAmount());
        assertEquals(2, t.taxSubtotals().size());
        assertEquals(TaxType.SALES_TAX, t.taxSubtotals().get(0).type());
        assertEquals(d("2773.00"), t.taxSubtotals().get(0).taxableAmount());
    }

    @Test
    void roundsHalfUpOncePerAmount() {
        LineItem l = LineItem.builder().description("x").quantity("1").unitPrice("0.25")
                .tax(LineTax.service("6")).build();
        assertEquals(d("0.02"), Totals.line(l).taxAmount()); // 0.015

        LineItem rate = LineItem.builder().description("x").quantity("1").unitPrice("99.99")
                .allowanceCharge(AllowanceCharge.discountRate("Member", "0.15")).tax(LineTax.notApplicable()).build();
        assertEquals(d("84.99"), Totals.line(rate).lineExtension()); // 99.99 - 15.00 (14.9985)

        LineItem fractional = LineItem.builder().description("x").quantity("3.333").unitPrice("1.115")
                .tax(LineTax.notApplicable()).build();
        assertEquals(d("3.72"), Totals.line(fractional).subtotal()); // 3.716295
    }

    @Test
    void perUnitAndExemptTaxes() {
        LineItem room = LineItem.builder().description("Deluxe room").quantity("3").unitPrice("250.00")
                .tax(LineTax.perUnit(TaxType.TOURISM_TAX, "10.00")).tax(LineTax.service("8")).build();
        Totals.LineTotals t = Totals.line(room);
        assertEquals(d("30.00"), t.taxes().get(0).taxAmount());
        assertEquals(d("3"), t.taxes().get(0).baseUnits());
        assertEquals(d("60.00"), t.taxes().get(1).taxAmount());
        assertEquals(d("90.00"), t.taxAmount());

        LineItem exempt = LineItem.builder().description("Rice").quantity("10").unitPrice("3.20")
                .tax(LineTax.exempt("Exempted goods under Sales Tax (Goods Exempted) Order")).build();
        assertEquals(d("0.00"), Totals.line(exempt).taxAmount());
    }

    @Test
    void documentChargesPrepaidAndRounding() {
        Invoice inv = TestData.invoice()
                .allowanceCharge(AllowanceCharge.discount("Loyalty", "50.00"))
                .allowanceCharge(AllowanceCharge.charge("Delivery", "25.00"))
                .prepaid(new PrepaidPayment("DEP-1", d("1000"), TestData.ISSUED))
                .rounding("-0.05")
                .build();
        Totals.DocumentTotals t = Totals.of(inv);
        assertEquals(d("50.00"), t.allowanceTotalAmount());
        assertEquals(d("25.00"), t.chargeTotalAmount());
        assertEquals(d("2868.00"), t.taxExclusiveAmount());
        assertEquals(d("3154.90"), t.taxInclusiveAmount());
        assertEquals(d("2154.85"), t.payableAmount());
    }

    @Test
    void cashRoundingToFiveSen() {
        assertEquals(d("-0.02"), Totals.cashRounding(d("3179.92"), d("0.05")));
        assertEquals(d("0.02"), Totals.cashRounding(d("3179.93"), d("0.05")));
        assertEquals(d("0.00"), Totals.cashRounding(d("3179.90"), d("0.05")));
    }

    @Test
    void foreignCurrencyTaxInMyr() {
        Totals.DocumentTotals t = Totals.of(TestData.invoice().currency("USD", "4.2150").build());
        assertEquals(d("1209.28"), t.taxAmountInMyr(d("4.2150"))); // 286.90 x 4.215 = 1209.2835
    }
}
