package com.replayroom.session;

/** Thrown when two branches cannot be merged; carries the first conflict group. */
public class ConflictException extends RuntimeException {

    private final transient Object conflictDetails;

    public ConflictException(String message, Object conflictDetails) {
        super(message);
        this.conflictDetails = conflictDetails;
    }

    public Object conflictDetails() {
        return conflictDetails;
    }
}
