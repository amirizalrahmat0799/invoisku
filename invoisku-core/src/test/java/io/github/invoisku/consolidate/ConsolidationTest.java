package io.github.invoisku.consolidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.invoisku.TestData;
import io.github.invoisku.calc.Totals;
import io.github.invoisku.codes.GeneralTin;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.LineTax;
import io.github.invoisku.validate.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class ConsolidationTest {

    private Consolidation september() {
        return Consolidation.of(TestData.supplier(), YearMonth.of(2026, 9))
                .issuedAt(TestData.ISSUED)
                .receipt("R-0001", "12.50", LineTax.service("8"))
                .receipt("R-0002", "8.00", LineTax.service("8"))
                .receipt("R-0003", "30.00", LineTax.notApplicable())
                .receipt("R-0004", "4.50", LineTax.service("8"));
    }

    @Test
    void summarisesByTaxTreatment() {
        Invoice inv = september().build("CINV-2026-09");
        assertEquals(GeneralTin.GENERAL_PUBLIC, inv.buyer().tin());
        assertEquals(2, inv.lines().size());
        assertEquals("R-0001 - R-0004", inv.lines().get(0).description());
        assertEquals(new BigDecimal("25.00"), inv.lines().get(0).unitPrice());
        assertEquals("004", inv.lines().get(0).classifications().get(0));
        assertEquals(LocalDate.of(2026, 9, 30), inv.period().end());
        Totals.DocumentTotals t = Totals.of(inv);
        assertEquals(new BigDecimal("2.00"), t.taxAmount());
        assertEquals(new BigDecimal("57.00"), t.payableAmount());
        assertTrue(new Validator(TestData.CLOCK).validate(inv).isValid(),
                new Validator(TestData.CLOCK).validate(inv).toString());
        assertEquals(0, new Validator(TestData.CLOCK).validate(inv).warnings().size());
    }

    @Test
    void oneLinePerReceipt() {
        Invoice inv = september().mode(Consolidation.Mode.PER_RECEIPT).build("CINV-2026-09");
        assertEquals(4, inv.lines().size());
        assertEquals("R-0003", inv.lines().get(2).description());
        assertEquals(new BigDecimal("57.00"), Totals.of(inv).payableAmount());
    }

    @Test
    void deadlineIsTheSeventh() {
        assertEquals(LocalDate.of(2027, 1, 7), Consolidation.deadline(YearMonth.of(2026, 12)));
    }
}
