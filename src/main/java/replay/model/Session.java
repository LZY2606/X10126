package replay.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Session {
    public String id;
    public Map<String, Object> definition;
    public Definition parsed;
    public String definitionHash;
    public long nextBranchSeq = 1;
    public long nextCheckpointSeq = 1;
    public LinkedHashMap<String, Branch> branches = new LinkedHashMap<>();

    public Branch mainBranch() {
        Branch m = branches.get("main");
        return m != null ? m : branches.values().iterator().next();
    }
}
