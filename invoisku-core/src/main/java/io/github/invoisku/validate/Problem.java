package io.github.invoisku.validate;

/**
 * One finding of the {@link Validator}.
 *
 * @param path    where it is, for example {@code buyer.address.city} or {@code lines[2].taxes[0]}
 * @param code    stable identifier, for example {@code REQUIRED} or {@code TOO_LONG}
 */
public record Problem(Severity severity, String path, String code, String message) {

    public enum Severity {
        /** LHDN would reject the document. */
        ERROR,
        /** Accepted by LHDN, but probably not what you meant. */
        WARNING
    }

    @Override
    public String toString() {
        return severity + " " + path + " [" + code + "] " + message;
    }
}
