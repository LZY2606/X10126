package com.replayroom.core;

/** Raised when an expression cannot be parsed or evaluated. Converted to action failure by the engine. */
public final class EvalException extends RuntimeException {
    public EvalException(String message) {
        super(message);
    }
}
