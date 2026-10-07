package io.github.invoisku.api;

import java.util.List;

/** Response types of {@link MyInvoisClient}. */
public final class Records {
    private Records() {}

    public record AcceptedDocument(String uuid, String invoiceCodeNumber) {}

    public record RejectedDocument(String invoiceCodeNumber, String errorCode, String message, List<String> details) {}

    /** The immediate answer to a submission: documents are queued, not yet validated. */
    public record SubmissionResult(
            String submissionUid, List<AcceptedDocument> accepted, List<RejectedDocument> rejected) {}

    /**
     * A document's state as LHDN reports it.
     *
     * @param status Submitted, Valid, Invalid or Cancelled
     * @param longId with the uuid, forms the public validation link; present once Valid
     */
    public record DocumentSummary(
            String uuid,
            String submissionUid,
            String longId,
            String internalId,
            String typeName,
            String typeVersionName,
            String issuerTin,
            String receiverId,
            String dateTimeIssued,
            String dateTimeValidated,
            String totalPayableAmount,
            String status) {

        public boolean isFinal() {
            return !"Submitted".equalsIgnoreCase(status);
        }
    }

    /** @param overallStatus in progress, valid, partially valid or invalid */
    public record SubmissionStatus(
            String submissionUid, int documentCount, String dateTimeReceived, String overallStatus,
            List<DocumentSummary> documents) {

        public boolean isInProgress() {
            return "in progress".equalsIgnoreCase(overallStatus);
        }
    }

    /** One validation error from the document details endpoint. */
    public record ValidationStep(String name, String status, String errorCode, String message, List<String> details) {}

    public record DocumentDetails(DocumentSummary summary, List<ValidationStep> validationSteps, String rawJson) {}
}
