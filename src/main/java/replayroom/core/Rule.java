package replayroom.core;

import java.util.ArrayList;
import java.util.List;

public class Rule {
    public String event;
    public String from;
    public String to;
    public String condition;
    public List<Action> actions = new ArrayList<>();
}
