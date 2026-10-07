package io.github.invoisku;

import io.github.invoisku.api.SubmittedDocument;
import io.github.invoisku.calc.Totals;
import io.github.invoisku.calc.Totals.DocumentTotals;
import io.github.invoisku.document.Invoice;
import io.github.invoisku.json.Json;
import io.github.invoisku.sign.DocumentSigner;
import io.github.invoisku.sign.SigningCredentials;
import io.github.invoisku.ubl.UblJson;
import io.github.invoisku.validate.Problem;
import io.github.invoisku.validate.ValidationResult;
import io.github.invoisku.validate.Validator;
import java.util.List;
import java.util.Map;

/**
 * The one-call path from an {@link Invoice} to a document ready to submit: validate, calculate, convert to UBL-JSON
 * and sign.
 *
 * <pre>{@code
 * EInvoices einvoices = new EInvoices(SigningCredentials.fromPkcs12(path, password));
 * Prepared p = einvoices.prepare(invoice);       // throws if LHDN would reject it
 * client.submit(List.of(p.submittedDocument()));
 * }</pre>
 */
public final class EInvoices {

    private final DocumentSigner signer;
    private final Validator validator;

    /** Signs version 1.1 documents with the given credentials. */
    public EInvoices(SigningCredentials credentials) {
        this(new DocumentSigner(credentials), new Validator());
    }

    /** {@code signer} may be null, in which case only version 1.0 (unsigned) documents can be prepared. */
    public EInvoices(DocumentSigner signer, Validator validator) {
        this.signer = signer;
        this.validator = validator;
    }

    /** A validated, calculated and (for version 1.1) signed document. */
    public record Prepared(Invoice invoice, DocumentTotals totals, String json, List<Problem> warnings) {
        public SubmittedDocument submittedDocument() {
            return SubmittedDocument.json(invoice.number(), json);
        }
    }

    public ValidationResult validate(Invoice invoice) {
        return validator.validate(invoice);
    }

    /** Throws {@link ValidationResult.InvalidDocumentException} if there are errors. */
    public Prepared prepare(Invoice invoice) {
        ValidationResult result = validator.validate(invoice).orThrow();
        DocumentTotals totals = Totals.of(invoice);
        Map<String, Object> tree = UblJson.toTree(invoice, totals);
        String json;
        if (Invoice.VERSION_1_1.equals(invoice.version())) {
            if (signer == null) throw new IllegalStateException("Version 1.1 documents must be signed");
            json = Json.write(signer.sign(tree));
        } else {
            json = Json.write(tree);
        }
        return new Prepared(invoice, totals, json, result.warnings());
    }
}
