package com.globalnewsflash.digest.domain;

import com.globalnewsflash.digest.infrastructure.DigestMapper.DigestItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
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
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(target.getBytes(StandardCharsets.UTF_8)));
            return "digest:" + date + ":" + hash;
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public static int length(String body) { return body.codePointCount(0, body.length()); }
}
