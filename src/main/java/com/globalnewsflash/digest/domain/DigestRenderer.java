package com.globalnewsflash.digest.domain;

import static com.globalnewsflash.shared.Hashing.sha256;

import com.globalnewsflash.digest.infrastructure.DigestMapper.DigestItem;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/** Copies complete, previously published briefs; it never invents a new title or factual claim. */
public final class DigestRenderer {
    private DigestRenderer() {}
    public static String render(String date, List<DigestItem> items) {
        LocalDate.parse(date);
        return "Global News Flash — Daily digest\n" + date + " (UTC)\n\n"
                + items.stream().map(DigestItem::body).collect(Collectors.joining("\n\n———\n\n"))
                + "\n\nSelected published briefs from the preceding 24 hours.";
    }
    public static String eventKey(String date, String target) {
        LocalDate.parse(date);
        return "digest:" + date + ":" + sha256(target);
    }
    public static int length(String body) { return body.codePointCount(0, body.length()); }
}
