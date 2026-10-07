# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `invoisku-core`, a zero-dependency Java 17 library:
  - Document model and builders for all eight e-invoice types (invoice, credit, debit and refund notes, and their
    self-billed versions), with references to the original e-invoice for adjustments.
  - Totals calculator in `BigDecimal` (line discounts and charges, percentage, per-unit and exempt taxes,
    document discounts and charges, prepayments, cash rounding).
  - UBL 2.1 JSON writer in MyInvois format, in UBL element order, with foreign-currency exchange rates.
  - Validator for mandatory fields, lengths, code tables, formats and the 72-hour issue window, reporting every
    problem at once.
  - XAdES signing for document version 1.1 (PKCS#12 or PEM keys) and a signature verifier.
  - MyInvois API client on `java.net.http`: token caching, intermediaries (`onbehalfof`), batched submission
    within MyInvois limits, retries with Retry-After, duplicate detection, submission and document status,
    cancel and reject, TIN validation with caching, TIN search, recent documents, validation links.
  - Monthly consolidated e-invoice builder for sales to the general public.
  - Code tables: e-invoice types, tax types, states, payment modes, classifications, general TINs.
