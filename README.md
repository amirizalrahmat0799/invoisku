# invoisku

[![CI](https://github.com/amirizalrahmat0799/invoisku/actions/workflows/ci.yml/badge.svg)](https://github.com/amirizalrahmat0799/invoisku/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-17%2B-blue)
![dependencies](https://img.shields.io/badge/dependencies-0-brightgreen)
[![license](https://img.shields.io/github/license/amirizalrahmat0799/invoisku)](LICENSE)

**Open-source engine for Malaysia's e-invoicing system, MyInvois (LHDN).** Build, validate, sign and submit
e-invoices from Java, with no dependencies beyond the JDK.

Every business in Malaysia is being phased into e-invoicing. Each invoice must be converted to UBL, signed with
the business's digital certificate, submitted to LHDN's API and tracked until it is validated. Commercial
middleware charges per document for this; Java shops (banks, ERPs, telcos, insurers) had no solid open-source
option. invoisku is that option.

> **Status: pre-release (0.1.0-SNAPSHOT).** The core library is complete and tested against a local mock of the
> MyInvois API. It has not yet been run against LHDN's sandbox, and the signature format is being checked against
> LHDN's signed samples. Don't use it for production submissions until 0.1.0 is released.

## What it does

| | |
|---|---|
| **Documents** | All 8 types: invoice, credit note, debit note, refund note, and the self-billed versions |
| **Totals** | Calculated in `BigDecimal` from the lines: discounts, charges, percentage and per-unit taxes, exemptions, prepayments, cash rounding |
| **UBL-JSON** | The UBL 2.1 JSON format MyInvois expects, in schema order |
| **Validation** | Mandatory fields, lengths, LHDN code tables, TIN, phone and email formats, the 72-hour window; all problems at once |
| **Signing** | XAdES signature for document version 1.1, from your PKCS#12 (.p12/.pfx) or PEM certificate, plus a verifier |
| **API client** | Login and token refresh, intermediaries (`onbehalfof`), submission split into batches within LHDN's limits, retries that respect Retry-After, duplicate detection, status polling, cancel and reject, TIN validation with caching |
| **Consolidation** | The monthly consolidated e-invoice for sales to the general public |

## Quick start

```java
Party supplier = Party.builder()
        .tin("C25845632020").brn("202001234567").sstNumber("W10-1808-32000012")
        .name("Kedai Kopi Sentosa Sdn Bhd")
        .address(Address.malaysia("Lot 66, Jalan Merdeka", "50480", "Kuala Lumpur", State.KUALA_LUMPUR))
        .phone("+60123456789").email("billing@sentosa.example")
        .msic("56103", "Restaurants and restaurant cum night clubs")
        .build();

Invoice invoice = Invoice.invoice()
        .number("INV-2026-0001")
        .supplier(supplier)
        .buyer(buyer)
        .payment(PaymentMode.BANK_TRANSFER, "5141-2345-6789")
        .line(LineItem.builder()
                .classification("003")              // computer, smartphone or tablet
                .description("Laptop 14\" Pro")
                .quantity("2", "C62")
                .unitPrice("1436.50")
                .discount("Promotion", "100.00")
                .tax(LineTax.sales("10"))
                .build())
        .build();

// validate, calculate, convert to UBL-JSON and sign
EInvoices einvoices = new EInvoices(SigningCredentials.fromPkcs12(Path.of("company.p12"), password));
EInvoices.Prepared prepared = einvoices.prepare(invoice);   // throws, listing every error, if LHDN would reject it

MyInvoisClient api = MyInvoisClient.builder(clientId, clientSecret)
        .environment(Environment.SANDBOX)
        .build();
SubmissionResult result = api.submit(List.of(prepared.submittedDocument()));

// LHDN validates asynchronously: poll until the submission is no longer in progress
SubmissionStatus status = api.getSubmission(result.submissionUid());
DocumentSummary doc = status.documents().get(0);
String qrLink = api.validationLink(doc.uuid(), doc.longId());   // print this as the QR code on the invoice
```

### Credit notes, foreign currency, consolidation

```java
Invoice.builder(DocumentType.CREDIT_NOTE)
        .adjusts(originalUuid, "INV-2026-0001")     // required for credit, debit and refund notes
        ...

Invoice.invoice().currency("USD", "4.2150")      // exchange rate to MYR is required for foreign currency
        ...

Invoice monthly = Consolidation.of(supplier, YearMonth.of(2026, 9))
        .receipts(receiptsFromYourPos)           // buyer becomes "General Public", classification 004
        .build("CINV-2026-09");
Consolidation.deadline(YearMonth.of(2026, 9));   // 2026-10-07
```

### Validation without throwing

```java
ValidationResult r = new Validator().validate(invoice);
r.errors();     // [ERROR buyer.phone [FORMAT] Not a phone number: call me, ...]
r.warnings();   // accepted by LHDN, but probably a mistake
```

## How totals are calculated

```
line subtotal        = quantity x unit price
line amount          = subtotal - line discounts + line charges
line tax             = line amount x percent / 100   or   quantity x amount per unit   or   0 if exempt

LineExtensionAmount  = sum of line amounts
TaxExclusiveAmount   = LineExtensionAmount - document discounts + document charges
TaxInclusiveAmount   = TaxExclusiveAmount + total tax
PayableAmount        = TaxInclusiveAmount - prepaid + rounding
```

Each amount is rounded half-up to 2 decimals once, never on intermediate products. Totals are always derived
from the lines, so they cannot disagree with them.

## Roadmap

- [x] `invoisku-core`: model, totals, UBL-JSON, validation, signing, API client, consolidation
- [ ] Signature checked byte for byte against LHDN's signed v1.1 samples
- [ ] Run against the LHDN sandbox
- [ ] `invoisku-spring-boot-starter`
- [ ] `invoisku-server`: self-hostable service with a submission queue, status polling, webhooks, monthly
      consolidation and an admin UI, in Docker Compose
- [ ] Mock MyInvois server for your own integration tests
- [ ] Release 0.1.0 to Maven Central

## Building

```bash
mvn verify
```

Java 17 or newer. The library has no runtime dependencies; tests use JUnit 5.

## Disclaimer

invoisku is an independent open-source project, not affiliated with or endorsed by Lembaga Hasil Dalam Negeri
Malaysia (LHDN). You remain responsible for the e-invoices you issue. Check LHDN's current guidelines and
specific feature notes, and test in the sandbox before going live.

## License

[Apache License 2.0](LICENSE) © Ahmad Amirizal Rahmat
