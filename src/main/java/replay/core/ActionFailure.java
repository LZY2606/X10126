package replay.core;

/** Raised when an action cannot be applied; the whole step is rolled back. */
public final class ActionFailure extends RuntimeException {
    public ActionFailure(String message) {
        super(message);
    }
}
