package replay.core;

/** 持久化 JSON 中使用的字段名常量。 */
final class Keys {
    private Keys() {
    }

    static final String DEFINITION = "definition";
    static final String DEF_FINGERPRINT = "definitionFingerprint";
    static final String DEF_VERSION = "version";
    static final String NAME = "name";
    static final String STATES = "states";
    static final String INITIAL_STATE = "initialState";
    static final String INITIAL_DATA = "initialData";
    static final String TRANSITIONS = "transitions";
    static final String SOURCES = "sources";
    static final String PRIORITY = "priority";

    static final String SESSIONS = "sessions";
    static final String SEQ = "seqCounter";
    static final String SESSION_ID = "id";
    static final String SEED = "seed";
    static final String EVENTS = "events";
    static final String CREATED_AT = "createdAt";
    static final String BRANCHES = "branches";
    static final String ACTIVE_BRANCH = "activeBranch";
    static final String FINGERPRINT = "fingerprint";

    static final String CURRENT = "current";
    static final String STATE = "state";
    static final String QUEUE = "queue";
    static final String RNG_USES = "rngUses";
    static final String STEPS = "steps";
    static final String CHECKPOINTS = "checkpoints";
    static final String ORIGIN = "origin";
    static final String ORIGIN_BRANCH = "originBranch";
    static final String ORIGIN_CHECKPOINT = "originCheckpoint";
    static final String ORIGIN_EVENTS = "originEvents";
    static final String RNG_SEED_AT_ORIGIN = "rngSeedAtOrigin";

    static final String CP_ID = "id";
    static final String CP_LABEL = "label";
    static final String CP_STEP_INDEX = "stepIndex";
    static final String CP_STATE = "state";
    static final String CP_CURRENT = "current";
    static final String CP_QUEUE = "queue";
    static final String CP_RNG_USES = "rngUses";
    static final String CP_DEF_FINGERPRINT = "definitionFingerprint";
    static final String CP_STEP_HASH = "lastStepHash";
    static final String CP_CREATED_AT = "createdAt";

    static final String STEP_INDEX = "index";
    static final String EVENT = "event";
    static final String TRANSITION = "transition";
    static final String BEFORE = "before";
    static final String BEFORE_STATE = "beforeState";
    static final String AFTER = "after";
    static final String AFTER_STATE = "afterState";
    static final String OUTPUTS = "outputs";
    static final String EMITTED = "emitted";
    static final String FAILURE = "failure";
    static final String RNG_ADVANCE = "rngAdvance";
    static final String HASH = "hash";

    static final String EV_ID = "id";
    static final String EV_KIND = "kind";
    static final String EV_TIME = "time";
    static final String EV_SOURCE = "source";
    static final String EV_SEQ = "seq";
    static final String EV_TYPE = "type";
    static final String EV_PAYLOAD = "payload";
}
