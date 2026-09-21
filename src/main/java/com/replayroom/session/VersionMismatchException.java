package com.replayroom.session;

/** Thrown when a checkpoint created under another definition version is used. */
public class VersionMismatchException extends RuntimeException {
    public VersionMismatchException(String message) {
        super(message);
    }
}
