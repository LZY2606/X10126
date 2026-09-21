package io.example.replay;

import java.util.LinkedHashMap;
import java.util.Map;

public class AppState {
    public MachineDefinition definition;
    public Map<String, Session> sessions = new LinkedHashMap<>();
}
