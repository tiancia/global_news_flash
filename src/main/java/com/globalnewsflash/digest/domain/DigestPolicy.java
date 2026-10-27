package com.globalnewsflash.digest.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

public final class DigestPolicy {
    private DigestPolicy() {}

    public static List<String> configurationProblems(int utcHour, int maxItems, int maxCharacters) {
        var reasons = new ArrayList<String>();
        if (utcHour < 0 || utcHour > 23) reasons.add("DIGEST_HOUR_INVALID");
        if (maxItems < 1 || maxItems > 20) reasons.add("DIGEST_ITEM_LIMIT_INVALID");
        if (maxCharacters < 200 || maxCharacters > 4096) reasons.add("DIGEST_LENGTH_INVALID");
        return List.copyOf(reasons);
    }

    public static boolean due(Instant now, int utcHour) { return now.atZone(ZoneOffset.UTC).getHour() >= utcHour; }

    public static List<Candidate> latestCandidates(List<Candidate> candidates) {
        var latest = new LinkedHashMap<String, Candidate>();
        for (var candidate : candidates) {
            var previous = latest.get(candidate.eventKey());
            if (previous == null || previous.eventVersion() < candidate.eventVersion()) latest.put(candidate.eventKey(), candidate);
        }
        return latest.values().stream().sorted(Comparator.comparing(Candidate::sentAt).reversed().thenComparing(Candidate::eventKey)).toList();
    }

    public static List<DigestItem> select(String date, List<DigestItem> eligible, int maxItems, int maxCharacters) {
        var selected = new ArrayList<DigestItem>();
        for (var item : eligible) {
            if (selected.size() >= maxItems) break;
            var expanded = new ArrayList<>(selected);
            expanded.add(item);
            if (DigestRenderer.length(DigestRenderer.render(date, expanded)) <= maxCharacters) selected.add(item);
        }
        return List.copyOf(selected);
    }
}
