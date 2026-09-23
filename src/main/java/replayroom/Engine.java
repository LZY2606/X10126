package replayroom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Engine {
    private Engine() {
    }

    public static boolean canStep(Branch branch) {
        return !branch.pendingInternal().isEmpty() || branch.nextExternal() < branch.externalEvents().size();
    }

    public static Branch step(Branch branch, Models.Definition definition) {
        if (!branch.definitionFingerprint().equals(definition.fingerprint())) {
            throw new ApiException(409, "definition_mismatch", "Branch was recorded with another definition fingerprint");
        }
        if (!canStep(branch)) {
            throw new ApiException(409, "branch_done", "Branch has no remaining events");
        }

        List<Models.EventEnvelope> pending = new ArrayList<>(branch.pendingInternal());
        List<Models.EventEnvelope> external = branch.externalEvents();
        int nextExternal = branch.nextExternal();
        Models.EventEnvelope event;
        if (!pending.isEmpty()) {
            event = pending.remove(0);
        } else {
            event = external.get(nextExternal++);
        }

        String currentState = branch.currentState();
        Map<String, Object> vars = new LinkedHashMap<>(branch.vars());
        long rngState = branch.rngState();
        Map<String, Object> before = snapshot(currentState, vars);
        List<Map<String, Object>> outputs = new ArrayList<>();
        List<Models.EventEnvelope> spawned = new ArrayList<>();
        String transitionLabel = null;
        String failure = null;

        List<Models.Transition> candidates = definition.matches(currentState, event.type());
        Models.Transition selected = null;
        for (Models.Transition candidate : candidates) {
            String condition = candidate.condition();
            if (condition == null || condition.isBlank() ||
                    Expression.condition(condition, Expression.context(vars, event.toMap(), rngState))) {
                selected = candidate;
                break;
            }
        }

        if (selected != null) {
            transitionLabel = selected.event() + ":" + selected.from() + "->" + (selected.to() == null ? currentState : selected.to());
            Map<String, Object> candidateVars = vars;
            long candidateRng = rngState;
            for (int i = 0; i < selected.actions().size(); i++) {
                Models.Action action = selected.actions().get(i);
                try {
                    Actions.Result result = Actions.execute(action, candidateVars, candidateRng, event.toMap(), branch.steps().size() + 1L, i + 1L, event.id());
                    candidateVars = result.vars();
                    candidateRng = result.rngState();
                    outputs.addAll(result.outputs());
                    spawned.addAll(result.events());
                } catch (Actions.ActionFailureException e) {
                    failure = "action[" + (i + 1) + "] " + action.type() + ": " + e.getMessage();
                    candidateVars = vars;
                    candidateRng = rngState;
                    spawned.clear();
                    break;
                }
            }
            if (failure == null) {
                vars = candidateVars;
                rngState = candidateRng;
                currentState = selected.to() == null || selected.to().isBlank() ? currentState : selected.to();
                pending.addAll(0, spawned);
            }
        }

        Map<String, Object> after = snapshot(currentState, vars);
        List<String> processed = new ArrayList<>(branch.processedExternalIds());
        if (!event.internal()) {
            processed.add(event.id());
        }
        List<Map<String, Object>> spawnedMaps = new ArrayList<>();
        spawned.forEach(spawn -> spawnedMaps.add(spawn.toMap()));
        long index = branch.steps().size() + 1L;
        String stepHash = hashStep(branch.lastTraceHash(), event, before, after, outputs, spawnedMaps, transitionLabel, failure != null, failure);
        Step step = new Step(
            index,
            event.id(),
            before,
            after,
            event.toMap(),
            outputs,
            spawnedMaps,
            transitionLabel,
            failure != null,
            failure,
            stepHash,
            branch.lastTraceHash()
        );
        List<Step> steps = new ArrayList<>(branch.steps());
        steps.add(step);

        return new Branch(
            branch.id(),
            branch.name(),
            branch.definitionFingerprint(),
            branch.baseCheckpointId(),
            branch.forkedFromBranchId(),
            branch.parentCheckpointId(),
            currentState,
            vars,
            rngState,
            external,
            pending,
            nextExternal,
            steps,
            processed,
            stepHash,
            branch.merged()
        );
    }

    public static String genesisHash(Models.Definition definition) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "genesis");
        payload.put("definitionFingerprint", definition.fingerprint());
        payload.put("initialState", definition.initialState());
        payload.put("initialVars", definition.initialVars());
        return Hashing.sha256(Json.canonical(payload));
    }

    private static String hashStep(
        String previous,
        Models.EventEnvelope event,
        Map<String, Object> before,
        Map<String, Object> after,
        List<Map<String, Object>> outputs,
        List<Map<String, Object>> spawned,
        String transition,
        boolean failed,
        String failure
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previous", previous);
        payload.put("event", event.toMap());
        payload.put("before", before);
        payload.put("after", after);
        payload.put("outputs", outputs);
        payload.put("spawnedInternal", spawned);
        payload.put("transition", transition);
        payload.put("failed", failed);
        payload.put("failure", failure);
        return Hashing.sha256(Json.canonical(payload));
    }

    static Map<String, Object> snapshot(String state, Map<String, Object> vars) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", state);
        snapshot.putAll(vars);
        return snapshot;
    }
}
