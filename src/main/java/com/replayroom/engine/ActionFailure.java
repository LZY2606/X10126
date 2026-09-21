package com.replayroom.engine;

/** Raised by the explicit fail action; rolls back the whole action transaction. */
public class ActionFailure extends RuntimeException {
    public ActionFailure(String message) {
        super(message);
    }
}
