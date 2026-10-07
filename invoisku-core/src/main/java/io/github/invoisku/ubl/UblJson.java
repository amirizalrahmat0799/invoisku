package io.github.invoisku.ubl;

import io.github.invoisku.calc.Totals;
import io.github.invoisku.calc.Totals.AppliedAllowanceCharge;
import io.github.invoisku.calc.Totals.DocumentTotals;
import io.github.invoisku.calc.Totals.LineTotals;
import io.github.invoisku.calc.Totals.TaxSubtotal;
import io.github.invoisku.document.Address;
import io.github.invoisku.document.DocumentReference;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.document.LineItem;
import io.github.invoisku.document.Party;
import io.github.invoisku.json.Json;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link Invoice} to the UBL 2.1 JSON that MyInvois accepts (format "JSON").
 *
 * <p>Every UBL element becomes an array of objects; a value sits in {@code "_"} next to its attributes, for example
 * {@code "InvoiceTypeCode":[{"_":"01","listVersionID":"1.1"}]}. Elements are written in UBL schema order. Amounts
 * are JSON numbers with 2 decimals.
 */
public final class UblJson {

    public static final String NS_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    public static final String NS_CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    public static final String NS_CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss'Z'");

    private UblJson() {}

    /** The full document as a mutable JSON tree (unsigned). */
    public static Map<String, Object> toTree(Invoice inv) {
        return toTree(inv, Totals.of(inv));
    }

    /** The full document as minified JSON text (unsigned). */
    public static String toJson(Invoice inv) {
        return Json.write(toTree(inv));
    }

    public static Map<String, Object> toTree(Invoice inv, DocumentTotals totals) {
        String cur = inv.currency();
        OffsetDateTime utc = inv.issuedAt().withOffsetSameInstant(ZoneOffset.UTC);
        Map<String, Object> d = new LinkedHashMap<>();

        d.put("ID", v(inv.number()));
        d.put("IssueDate", v(utc.toLocalDate().toString()));
        d.put("IssueTime", v(TIME.format(utc)));
        d.put("InvoiceTypeCode", v(inv.type().code(), "listVersionID", inv.version()));
        d.put("DocumentCurrencyCode", v(cur));
        d.put("TaxCurrencyCode", v("MYR"));

        if (inv.period() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            if (inv.period().start() != null) p.put("StartDate", v(inv.period().start().toString()));
            if (inv.period().end() != null) p.put("EndDate", v(inv.period().end().toString()));
            if (inv.period().frequency() != null) p.put("Description", v(inv.period().frequency()));
            d.put("InvoicePeriod", one(p));
        }

        List<Object> billing = new ArrayList<>();
        for (DocumentReference ref : inv.originalInvoices()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ID", v(ref.internalId()));
            r.put("UUID", v(ref.uuid()));
            billing.add(obj("InvoiceDocumentReference", one(r)));
        }
        if (inv.billReference() != null) {
            billing.add(obj("AdditionalDocumentReference", one(obj("ID", v(inv.billReference())))));
        }
        if (!billing.isEmpty()) d.put("BillingReference", billing);

        List<Object> additional = new ArrayList<>();
        if (inv.customsFormReference() != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ID", v(inv.customsFormReference()));
            r.put("DocumentType", v("CustomsImportForm"));
            additional.add(r);
        }
        if (inv.freeTradeAgreement() != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ID", v("FTA"));
            r.put("DocumentType", v("FreeTradeAgreement"));
            r.put("DocumentDescription", v(inv.freeTradeAgreement()));
            additional.add(r);
        }
        if (inv.incoterms() != null) additional.add(obj("ID", v(inv.incoterms())));
        if (!additional.isEmpty()) d.put("AdditionalDocumentReference", additional);

        d.put("AccountingSupplierParty", one(obj("Party", one(party(inv.supplier(), true)))));
        d.put("AccountingCustomerParty", one(obj("Party", one(party(inv.buyer(), false)))));

        if (inv.shippingRecipient() != null) {
            Party s = inv.shippingRecipient();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("PartyLegalEntity", one(obj("RegistrationName", v(s.name()))));
            if (s.address() != null) p.put("PostalAddress", one(address(s.address())));
            p.put("PartyIdentification", identification(s));
            d.put("Delivery", one(obj("DeliveryParty", one(p))));
        }

        if (inv.paymentMode() != null) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("PaymentMeansCode", v(inv.paymentMode().code()));
            if (inv.supplierBankAccount() != null) {
                pm.put("PayeeFinancialAccount", one(obj("ID", v(inv.supplierBankAccount()))));
            }
            d.put("PaymentMeans", one(pm));
        }
        if (inv.paymentTerms() != null) d.put("PaymentTerms", one(obj("Note", v(inv.paymentTerms()))));

        if (inv.prepaid() != null) {
            Map<String, Object> pp = new LinkedHashMap<>();
            if (inv.prepaid().reference() != null) pp.put("ID", v(inv.prepaid().reference()));
            pp.put("PaidAmount", amount(totals.prepaidAmount(), cur));
            if (inv.prepaid().paidAt() != null) {
                OffsetDateTime paid = inv.prepaid().paidAt().withOffsetSameInstant(ZoneOffset.UTC);
                pp.put("PaidDate", v(paid.toLocalDate().toString()));
                pp.put("PaidTime", v(TIME.format(paid)));
            }
            d.put("PrepaidPayment", one(pp));
        }

        if (!totals.allowanceCharges().isEmpty()) {
            d.put("AllowanceCharge", allowanceCharges(totals.allowanceCharges(), cur));
        }

        if (inv.isForeignCurrency()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("SourceCurrencyCode", v(cur));
            x.put("TargetCurrencyCode", v("MYR"));
            x.put("CalculationRate", v(inv.exchangeRate()));
            d.put("TaxExchangeRate", one(x));
        }

        List<Object> taxTotals = new ArrayList<>();
        taxTotals.add(taxTotal(totals.taxAmount(), totals.taxSubtotals(), cur, false));
        if (inv.isForeignCurrency() && inv.exchangeRate() != null) {
            // tax in the tax currency (MYR), as LHDN's foreign-currency sample does
            taxTotals.add(obj("TaxAmount", amount(totals.taxAmountInMyr(inv.exchangeRate()), "MYR")));
        }
        d.put("TaxTotal", taxTotals);

        Map<String, Object> lmt = new LinkedHashMap<>();
        lmt.put("LineExtensionAmount", amount(totals.lineExtensionAmount(), cur));
        lmt.put("TaxExclusiveAmount", amount(totals.taxExclusiveAmount(), cur));
        lmt.put("TaxInclusiveAmount", amount(totals.taxInclusiveAmount(), cur));
        lmt.put("AllowanceTotalAmount", amount(totals.allowanceTotalAmount(), cur));
        lmt.put("ChargeTotalAmount", amount(totals.chargeTotalAmount(), cur));
        if (totals.prepaidAmount().signum() != 0) lmt.put("PrepaidAmount", amount(totals.prepaidAmount(), cur));
        lmt.put("PayableRoundingAmount", amount(totals.payableRoundingAmount(), cur));
        lmt.put("PayableAmount", amount(totals.payableAmount(), cur));
        d.put("LegalMonetaryTotal", one(lmt));

        List<Object> lines = new ArrayList<>();
        for (LineTotals lt : totals.lines()) lines.add(line(lt, cur));
        d.put("InvoiceLine", lines);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_D", NS_INVOICE);
        root.put("_A", NS_CAC);
        root.put("_B", NS_CBC);
        root.put("Invoice", one(d));
        return root;
    }

    private static Map<String, Object> line(LineTotals lt, String cur) {
        LineItem item = lt.line();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("ID", v(item.id()));
        l.put("InvoicedQuantity", item.unitCode() == null ? v(item.quantity())
                : v(item.quantity(), "unitCode", item.unitCode()));
        l.put("LineExtensionAmount", amount(lt.lineExtension(), cur));
        if (!lt.allowanceCharges().isEmpty()) l.put("AllowanceCharge", allowanceCharges(lt.allowanceCharges(), cur));
        l.put("TaxTotal", one(taxTotal(lt.taxAmount(), lt.taxes(), cur, true)));

        Map<String, Object> it = new LinkedHashMap<>();
        List<Object> classes = new ArrayList<>();
        if (item.tariffCode() != null) {
            classes.add(obj("ItemClassificationCode", v(item.tariffCode(), "listID", "PTC")));
        }
        for (String c : item.classifications()) {
            classes.add(obj("ItemClassificationCode", v(c, "listID", "CLASS")));
        }
        it.put("CommodityClassification", classes);
        it.put("Description", v(item.description()));
        if (item.originCountry() != null) {
            it.put("OriginCountry", one(obj("IdentificationCode", v(item.originCountry()))));
        }
        l.put("Item", one(it));
        l.put("Price", one(obj("PriceAmount", v(item.unitPrice(), "currencyID", cur))));
        l.put("ItemPriceExtension", one(obj("Amount", amount(lt.subtotal(), cur))));
        return l;
    }

    private static Map<String, Object> taxTotal(BigDecimal total, List<TaxSubtotal> subs, String cur, boolean line) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("TaxAmount", amount(total, cur));
        List<Object> list = new ArrayList<>();
        for (TaxSubtotal s : subs) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("TaxableAmount", amount(s.taxableAmount(), cur));
            st.put("TaxAmount", amount(s.taxAmount(), cur));
            if (line && s.baseUnits() != null) {
                st.put("BaseUnitMeasure", v(s.baseUnits()));
                st.put("PerUnitAmount", amount(s.perUnitAmount(), cur));
            } else if (line && s.percent() != null) {
                st.put("Percent", v(s.percent()));
            }
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("ID", v(s.type().code()));
            if (s.exemptionReason() != null) cat.put("TaxExemptionReason", v(s.exemptionReason()));
            Map<String, Object> scheme = new LinkedHashMap<>();
            scheme.put("_", "OTH");
            scheme.put("schemeID", "UN/ECE 5153");
            scheme.put("schemeAgencyID", "6");
            cat.put("TaxScheme", one(obj("ID", one(scheme))));
            st.put("TaxCategory", one(cat));
            list.add(st);
        }
        t.put("TaxSubtotal", list);
        return t;
    }

    private static List<Object> allowanceCharges(List<AppliedAllowanceCharge> list, String cur) {
        List<Object> out = new ArrayList<>();
        for (AppliedAllowanceCharge a : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ChargeIndicator", v(a.source().charge()));
            if (a.source().reason() != null) m.put("AllowanceChargeReason", v(a.source().reason()));
            if (a.source().rate() != null) m.put("MultiplierFactorNumeric", v(a.source().rate()));
            m.put("Amount", amount(a.amount(), cur));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> party(Party p, boolean supplier) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (supplier && p.msicCode() != null) {
            m.put("IndustryClassificationCode", v(p.msicCode(), "name", p.businessActivity()));
        }
        m.put("PartyIdentification", identification(p));
        if (p.address() != null) m.put("PostalAddress", one(address(p.address())));
        m.put("PartyLegalEntity", one(obj("RegistrationName", v(p.name()))));
        Map<String, Object> contact = new LinkedHashMap<>();
        if (p.phone() != null) contact.put("Telephone", v(p.phone()));
        if (p.email() != null) contact.put("ElectronicMail", v(p.email()));
        if (!contact.isEmpty()) m.put("Contact", one(contact));
        return m;
    }

    private static List<Object> identification(Party p) {
        List<Object> ids = new ArrayList<>();
        ids.add(obj("ID", v(p.tin(), "schemeID", "TIN")));
        if (p.idType() != null) ids.add(obj("ID", v(p.idValue(), "schemeID", p.idType().name())));
        ids.add(obj("ID", v(p.sstNumber() == null ? "NA" : p.sstNumber(), "schemeID", "SST")));
        ids.add(obj("ID", v(p.tourismTaxNumber() == null ? "NA" : p.tourismTaxNumber(), "schemeID", "TTX")));
        return ids;
    }

    private static Map<String, Object> address(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("CityName", v(a.city()));
        if (a.postalZone() != null && !a.postalZone().isEmpty()) m.put("PostalZone", v(a.postalZone()));
        m.put("CountrySubentityCode", v(a.state().code()));
        List<Object> lines = new ArrayList<>();
        for (String line : a.lines()) lines.add(obj("Line", v(line)));
        m.put("AddressLine", lines);
        Map<String, Object> code = new LinkedHashMap<>();
        code.put("_", a.country());
        code.put("listID", "ISO3166-1");
        code.put("listAgencyID", "6");
        m.put("Country", one(obj("IdentificationCode", one(code))));
        return m;
    }

    // ------------------------------------------------------------------ tree helpers

    /** {@code [{"_": value}]} */
    public static List<Object> v(Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_", value);
        return one(m);
    }

    /** {@code [{"_": value, attr: attrValue}]} */
    public static List<Object> v(Object value, String attr, Object attrValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_", value);
        m.put(attr, attrValue);
        return one(m);
    }

    static List<Object> amount(BigDecimal value, String currency) {
        return v(value == null ? null : Totals.money(value), "currencyID", currency);
    }

    public static Map<String, Object> obj(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    public static List<Object> one(Object o) {
        List<Object> l = new ArrayList<>(1);
        l.add(o);
        return l;
    }
}
