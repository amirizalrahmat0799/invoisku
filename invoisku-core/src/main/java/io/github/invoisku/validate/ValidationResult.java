package io.github.invoisku.validate;

import java.util.List;
import java.util.stream.Collectors;

/** The outcome of {@link Validator#validate}. */
public record ValidationResult(List<Problem> problems) {

    public ValidationResult {
        problems = List.copyOf(problems);
    }

    public boolean isValid() {
        return errors().isEmpty();
    }

    public List<Problem> errors() {
        return problems.stream().filter(p -> p.severity() == Problem.Severity.ERROR).toList();
    }

    public List<Problem> warnings() {
        return problems.stream().filter(p -> p.severity() == Problem.Severity.WARNING).toList();
    }

    /** Throws {@link InvalidDocumentException} listing every error, if there are any. */
    public ValidationResult orThrow() {
        if (!isValid()) throw new InvalidDocumentException(this);
        return this;
    }

    @Override
    public String toString() {
        return problems.stream().map(Problem::toString).collect(Collectors.joining("\n"));
    }

    /** Thrown by {@link #orThrow()}. */
    public static final class InvalidDocumentException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient ValidationResult result;

        InvalidDocumentException(ValidationResult result) {
            super("The e-invoice has " + result.errors().size() + " error(s):\n"
                    + result.errors().stream().map(Problem::toString).collect(Collectors.joining("\n")));
            this.result = result;
        }

        public ValidationResult result() {
            return result;
        }
    }
}
