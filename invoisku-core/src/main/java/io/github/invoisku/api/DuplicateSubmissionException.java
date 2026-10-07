package io.github.invoisku.api;

import java.time.Duration;
import java.util.List;

/**
 * MyInvois refused a submission because the identical payload was submitted in the last few minutes (HTTP 422).
 * Do not resubmit; look up the earlier submission instead, or wait for {@link #retryAfter()}.
 */
public class DuplicateSubmissionException extends MyInvoisException {
    private static final long serialVersionUID = 1L;

    public DuplicateSubmissionException(String message, List<String> details, Duration retryAfter) {
        super(422, "DuplicateSubmission", message, details, retryAfter);
    }
}
