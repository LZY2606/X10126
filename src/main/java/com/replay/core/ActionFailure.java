package com.replay.core;

/** Thrown when an action fails; triggers rollback of the current event's effects. */
public class ActionFailure extends RuntimeException {
    public ActionFailure(String message) { super(message); }
}
