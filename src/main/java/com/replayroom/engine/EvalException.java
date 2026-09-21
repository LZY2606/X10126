package com.replayroom.engine;

/** Raised for expression evaluation problems; treated as action failure. */
public class EvalException extends RuntimeException {
    public EvalException(String message) {
        super(message);
    }
}
