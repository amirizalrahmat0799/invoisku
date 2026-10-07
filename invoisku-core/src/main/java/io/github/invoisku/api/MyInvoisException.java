package io.github.invoisku.api;

import java.time.Duration;
import java.util.List;

/**
 * An error response from MyInvois, or a failure to reach it.
 *
 * @see DuplicateSubmissionException
 */
public class MyInvoisException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final transient List<String> details;
    private final transient Duration retryAfter;

    public MyInvoisException(int status, String code, String message, List<String> details, Duration retryAfter) {
        super(status + " " + (code == null ? "" : code + ": ") + message
                + (details == null || details.isEmpty() ? "" : " " + details));
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
        this.retryAfter = retryAfter;
    }

    public MyInvoisException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = null;
        this.details = List.of();
        this.retryAfter = null;
    }

    /** HTTP status, or 0 if the request did not get a response. */
    public int status() {
        return status;
    }

    /** LHDN's error code, such as {@code OperationPeriodOver} or {@code IncorrectState}. */
    public String code() {
        return code;
    }

    public List<String> details() {
        return details;
    }

    /** From the Retry-After header, when LHDN sent one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** True for 429, 5xx and network failures: worth trying again later. */
    public boolean isRetryable() {
        return status == 0 || status == 429 || status >= 500;
    }
}
