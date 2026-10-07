package io.github.invoisku.ubl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.invoisku.TestData;
import io.github.invoisku.codes.DocumentType;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.InvoicePeriod;
import io.github.invoisku.json.Json;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UblJsonTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> doc(Invoice inv) {
        Map<String, Object> root = UblJson.toTree(inv);
        return (Map<String, Object>) ((List<Object>) root.get("Invoice")).get(0);
    }

    @Test
    void writesTheUblJsonEnvelope() {
        String json = UblJson.toJson(TestData.invoice().build());
        assertTrue(json.startsWith("{\"_D\":\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\","
                + "\"_A\":\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\","
                + "\"_B\":\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\","
                + "\"Invoice\":[{\"ID\":[{\"_\":\"INV-2026-0001\"}],"
                + "\"IssueDate\":[{\"_\":\"2026-10-01\"}],\"IssueTime\":[{\"_\":\"01:30:00Z\"}],"
                + "\"InvoiceTypeCode\":[{\"_\":\"01\",\"listVersionID\":\"1.1\"}],"
                + "\"DocumentCurrencyCode\":[{\"_\":\"MYR\"}],\"TaxCurrencyCode\":[{\"_\":\"MYR\"}],"), json);
    }

    @Test
    void elementsFollowUblOrder() {
        List<String> keys = new ArrayList<>(doc(TestData.invoice().build()).keySet());
        assertEquals(List.of("ID", "IssueDate", "IssueTime", "InvoiceTypeCode", "DocumentCurrencyCode",
                "TaxCurrencyCode", "AccountingSupplierParty", "AccountingCustomerParty", "PaymentMeans", "TaxTotal",
                "LegalMonetaryTotal", "InvoiceLine"), keys);
    }

    @Test
    void writesPartiesWithAllIdentifiers() {
        String json = UblJson.toJson(TestData.invoice().build());
        assertTrue(json.contains("\"IndustryClassificationCode\":[{\"_\":\"56103\","
                + "\"name\":\"Restaurants and restaurant cum night clubs\"}]"));
        assertTrue(json.contains("\"PartyIdentification\":[{\"ID\":[{\"_\":\"C25845632020\",\"schemeID\":\"TIN\"}]},"
                + "{\"ID\":[{\"_\":\"202001234567\",\"schemeID\":\"BRN\"}]},"
                + "{\"ID\":[{\"_\":\"W10-1808-32000012\",\"schemeID\":\"SST\"}]},"
                + "{\"ID\":[{\"_\":\"NA\",\"schemeID\":\"TTX\"}]}]"));
        assertTrue(json.contains("\"CountrySubentityCode\":[{\"_\":\"14\"}],\"AddressLine\":[{\"Line\":[{\"_\":"
                + "\"Lot 66, Jalan Merdeka\"}]},{\"Line\":[{\"_\":\"Taman Sentosa\"}]}],\"Country\":[{"
                + "\"IdentificationCode\":[{\"_\":\"MYS\",\"listID\":\"ISO3166-1\",\"listAgencyID\":\"6\"}]}]"));
        assertTrue(json.contains("\"Contact\":[{\"Telephone\":[{\"_\":\"+60198765432\"}],"
                + "\"ElectronicMail\":[{\"_\":\"siti@example.com\"}]}]"));
    }

    @Test
    void writesAmountsAsNumbersWithCurrency() {
        String json = UblJson.toJson(TestData.invoice().build());
        assertTrue(json.contains("\"LegalMonetaryTotal\":[{\"LineExtensionAmount\":[{\"_\":2893.00,\"currencyID\":\"MYR\"}],"
                + "\"TaxExclusiveAmount\":[{\"_\":2893.00,\"currencyID\":\"MYR\"}],"
                + "\"TaxInclusiveAmount\":[{\"_\":3179.90,\"currencyID\":\"MYR\"}],"
                + "\"AllowanceTotalAmount\":[{\"_\":0.00,\"currencyID\":\"MYR\"}],"
                + "\"ChargeTotalAmount\":[{\"_\":0.00,\"currencyID\":\"MYR\"}],"
                + "\"PayableRoundingAmount\":[{\"_\":0.00,\"currencyID\":\"MYR\"}],"
                + "\"PayableAmount\":[{\"_\":3179.90,\"currencyID\":\"MYR\"}]}]"), json);
    }

    @Test
    void writesLines() {
        String json = UblJson.toJson(TestData.invoice().build());
        assertTrue(json.contains("\"InvoiceLine\":[{\"ID\":[{\"_\":\"1\"}],"
                + "\"InvoicedQuantity\":[{\"_\":2,\"unitCode\":\"C62\"}],"
                + "\"LineExtensionAmount\":[{\"_\":2773.00,\"currencyID\":\"MYR\"}],"
                + "\"AllowanceCharge\":[{\"ChargeIndicator\":[{\"_\":false}],\"AllowanceChargeReason\":[{\"_\":\"Promotion\"}],"
                + "\"Amount\":[{\"_\":100.00,\"currencyID\":\"MYR\"}]}],"
                + "\"TaxTotal\":[{\"TaxAmount\":[{\"_\":277.30,\"currencyID\":\"MYR\"}],\"TaxSubtotal\":[{"
                + "\"TaxableAmount\":[{\"_\":2773.00,\"currencyID\":\"MYR\"}],\"TaxAmount\":[{\"_\":277.30,\"currencyID\":\"MYR\"}],"
                + "\"Percent\":[{\"_\":10}],\"TaxCategory\":[{\"ID\":[{\"_\":\"01\"}],\"TaxScheme\":[{\"ID\":[{\"_\":\"OTH\","
                + "\"schemeID\":\"UN/ECE 5153\",\"schemeAgencyID\":\"6\"}]}]}]}]}],"
                + "\"Item\":[{\"CommodityClassification\":[{\"ItemClassificationCode\":[{\"_\":\"003\",\"listID\":\"CLASS\"}]}],"
                + "\"Description\":[{\"_\":\"Laptop 14\\\" Pro\"}]}],"
                + "\"Price\":[{\"PriceAmount\":[{\"_\":1436.50,\"currencyID\":\"MYR\"}]}],"
                + "\"ItemPriceExtension\":[{\"Amount\":[{\"_\":2873.00,\"currencyID\":\"MYR\"}]}]}"), json);
    }

    @Test
    void foreignCurrencyAddsExchangeRateAndMyrTax() {
        String json = UblJson.toJson(TestData.invoice().currency("USD", "4.2150").build());
        assertTrue(json.contains("\"DocumentCurrencyCode\":[{\"_\":\"USD\"}],\"TaxCurrencyCode\":[{\"_\":\"MYR\"}]"));
        assertTrue(json.contains("\"TaxExchangeRate\":[{\"SourceCurrencyCode\":[{\"_\":\"USD\"}],"
                + "\"TargetCurrencyCode\":[{\"_\":\"MYR\"}],\"CalculationRate\":[{\"_\":4.2150}]}]"));
        assertTrue(json.contains("{\"TaxAmount\":[{\"_\":1209.28,\"currencyID\":\"MYR\"}]}"));
        assertTrue(json.contains("\"PayableAmount\":[{\"_\":3179.90,\"currencyID\":\"USD\"}]"));
    }

    @Test
    void creditNoteReferencesTheOriginal() {
        Invoice cn = TestData.invoice().build();
        Invoice note = Invoice.builder(DocumentType.CREDIT_NOTE).number("CN-1").issuedAt(TestData.ISSUED)
                .supplier(cn.supplier()).buyer(cn.buyer()).lines(cn.lines())
                .adjusts("F9D425P6DS7D8IU", "INV-2026-0001").build();
        String json = UblJson.toJson(note);
        assertTrue(json.contains("\"InvoiceTypeCode\":[{\"_\":\"02\",\"listVersionID\":\"1.1\"}]"));
        assertTrue(json.contains("\"BillingReference\":[{\"InvoiceDocumentReference\":[{\"ID\":[{\"_\":\"INV-2026-0001\"}],"
                + "\"UUID\":[{\"_\":\"F9D425P6DS7D8IU\"}]}]}]"));
    }

    @Test
    void optionalSections() {
        Invoice inv = TestData.invoice()
                .period(new InvoicePeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "Monthly"))
                .billReference("BILL-77").customsFormReference("K1-1234").incoterms("CIF")
                .paymentTerms("30 days").build();
        Map<String, Object> d = doc(inv);
        assertEquals("[{\"StartDate\":[{\"_\":\"2026-09-01\"}],\"EndDate\":[{\"_\":\"2026-09-30\"}],"
                + "\"Description\":[{\"_\":\"Monthly\"}]}]", Json.write(d.get("InvoicePeriod")));
        assertEquals("[{\"AdditionalDocumentReference\":[{\"ID\":[{\"_\":\"BILL-77\"}]}]}]",
                Json.write(d.get("BillingReference")));
        assertEquals("[{\"ID\":[{\"_\":\"K1-1234\"}],\"DocumentType\":[{\"_\":\"CustomsImportForm\"}]},"
                + "{\"ID\":[{\"_\":\"CIF\"}]}]", Json.write(d.get("AdditionalDocumentReference")));
        assertFalse(d.containsKey("Delivery"));
    }
}
