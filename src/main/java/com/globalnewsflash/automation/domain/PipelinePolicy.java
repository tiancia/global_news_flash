package com.globalnewsflash.automation.domain;

import com.globalnewsflash.editorial.domain.EditorialPolicy;
import com.globalnewsflash.ingestion.domain.SourceMaterial;
import com.globalnewsflash.ingestion.domain.SourcePolicy;
import com.globalnewsflash.publication.domain.PublicationOperation;
import java.util.*;

public final class PipelinePolicy {
    private PipelinePolicy() {}

    public static String requireSyntheticRequest(List<SourceMaterial> input, EditorialPolicy policy) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("At least one material is required");
        if (policy == null) throw new IllegalArgumentException("An explicit policy is required");
        if (input.stream().anyMatch(m -> !m.synthetic())) throw new IllegalArgumentException("This build accepts synthetic materials only");
        String key = input.getFirst().eventKey();
        if (input.stream().anyMatch(m -> !key.equals(m.eventKey()))) throw new IllegalArgumentException("One event per processing request is required");
        return key;
    }

    public static String requireLiveRequest(List<SourceMaterial> input) {
        if (input == null || input.isEmpty() || input.stream().anyMatch(SourceMaterial::synthetic)) throw new IllegalArgumentException("Real evidence is required");
        String key = input.getFirst().eventKey();
        if (input.stream().anyMatch(m -> !key.equals(m.eventKey()))) throw new IllegalArgumentException("One event per live request is required");
        return key;
    }

    public static List<String> admission(List<SourceMaterial> input, List<SourcePolicy> sources, boolean firstOnly) {
        var reasons = new ArrayList<String>();
        for (var material : input) {
            var source = sources.stream().filter(s -> s.sourceId().equals(material.sourceId())).findFirst();
            reasons.addAll(source.isEmpty() ? List.of("SOURCE_NOT_REGISTERED") : source.get().check(material));
            if (firstOnly && !reasons.isEmpty()) break;
        }
        return List.copyOf(reasons);
    }

    public static String editorialState(boolean eligible, List<String> reasons) {
        return eligible ? "ELIGIBLE" : reasons.stream().anyMatch(r -> r.contains("EXPIRED") || r.contains("STALE")) ? "EXPIRED" : "HELD";
    }

    public static List<String> revisionRejections(boolean hasPrevious, PublicationOperation original, String revisionMode) {
        if (!hasPrevious || (original != null && !"HOLD".equals(revisionMode))) return List.of();
        return List.of(original == null ? "ORIGINAL_DELIVERY_NOT_CONFIRMED" : "REVISION_POLICY_NOT_READY");
    }

    public static boolean deferred(List<String> reasons) {
        return reasons.stream().anyMatch(r -> Set.of("BUDGET_EXHAUSTED", "BUDGET_NOT_CONFIGURED", "ORIGINAL_DELIVERY_NOT_CONFIRMED").contains(r));
    }


    public static void requireConservativeBudget(long allocation, long required) {
        if (allocation < required) throw new IllegalArgumentException("A conservative request budget is required");
    }
}
