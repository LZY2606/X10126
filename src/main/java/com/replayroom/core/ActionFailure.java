package com.replayroom.core;

/** An action failed while applying a rule; the engine rolls the step back but records it. */
public final class ActionFailure extends Exception {
    public ActionFailure(String message) {
        super(message);
    }
}
