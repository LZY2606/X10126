package replayroom.core;

import com.fasterxml.jackson.databind.JsonNode;

public class Checkpoint {
    public String id;
    public String name;
    public String definitionFingerprint;
    public JsonNode snapshot;
}
