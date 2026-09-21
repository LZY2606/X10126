package com.replayroom.session;

import com.replayroom.engine.StepResult;
import com.replayroom.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the per-step trace records and maintains the chained trajectory
 * hash. Each hash binds the previous hash, the event, transition status,
 * before/after state and data, outputs and failure text, so a replayed
 * trajectory hashes identically only when every detail matched.
 */
public final class Steps {

    private Steps() {
    }

    public static Map<String, Object> buildRecord(StepResult result, String previousHash) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("event", result.event);
        record.put("status", result.status);
        record.put("matched", result.matched);
        record.put("stateBefore", result.stateBefore);
        record.put("stateAfter", result.stateAfter);
        record.put("dataBefore", result.dataBefore);
        record.put("dataAfter", result.dataAfter);
        record.put("outputs", result.outputs);
        record.put("failure", result.failure);
        record.put("rngBefore", result.rngBefore);
        record.put("rngAfter", result.rngAfter);
        record.put("prevHash", previousHash);

        Map<String, Object> hashMaterial = new LinkedHashMap<>();
        hashMaterial.put("event", result.event);
        hashMaterial.put("status", result.status);
        hashMaterial.put("matched", result.matched);
        hashMaterial.put("stateBefore", result.stateBefore);
        hashMaterial.put("stateAfter", result.stateAfter);
        hashMaterial.put("dataBefore", result.dataBefore);
        hashMaterial.put("dataAfter", result.dataAfter);
        hashMaterial.put("outputs", result.outputs);
        hashMaterial.put("failure", result.failure);
        hashMaterial.put("rngBefore", result.rngBefore);
        hashMaterial.put("rngAfter", result.rngAfter);
        hashMaterial.put("prevHash", previousHash);
        record.put("hash", Json.fingerprint(hashMaterial));
        return record;
    }

    public static String rootHash(String definitionFingerprint, String initialState,
                                  Object initialData, long seed) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("definitionFingerprint", definitionFingerprint);
        material.put("initialState", initialState);
        material.put("initialData", initialData);
        material.put("seed", seed);
        return Json.fingerprint(material);
    }
}
