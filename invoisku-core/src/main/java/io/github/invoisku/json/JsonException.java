package io.github.invoisku.json;

/** Thrown for malformed JSON. */
public class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int position;

    public JsonException(String message, int position) {
        super(message);
        this.position = position;
    }

    /** Character offset where the problem was found. */
    public int position() {
        return position;
    }
}
