package com.globalnewsflash.digest.application;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.digest.domain.DigestRenderer;
import com.globalnewsflash.digest.infrastructure.DigestMapper;
import com.globalnewsflash.digest.infrastructure.DigestMapper.DigestItem;
import com.globalnewsflash.digest.infrastructure.DigestProperties;
import com.globalnewsflash.publication.application.PublicationGuard;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.infrastructure.TelegramProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Digest routing is only a lookup hint; all authority comes from the saved snapshot and its evidence. */
@Component
public final class DigestPublicationGuard implements PublicationGuard {
    private final DigestMapper mapper;
    private final DigestProperties properties;
    private final DigestEvidenceValidator evidence;
    private final RuntimeProperties runtime;
    private final TelegramProperties telegram;
    private final JsonMapper json;
    private final Clock clock;

    public DigestPublicationGuard(DigestMapper mapper, DigestProperties properties, DigestEvidenceValidator evidence,
            RuntimeProperties runtime, TelegramProperties telegram, JsonMapper json, Clock clock) {
        this.mapper = mapper; this.properties = properties; this.evidence = evidence; this.runtime = runtime;
        this.telegram = telegram; this.json = json; this.clock = clock;
    }

    public boolean handles(PublicationOperation operation) {
        return operation.eventKey().startsWith("digest:") || mapper.byPublication(operation.id()) != null;
    }

    @Override
    public List<String> rejectionReasons(PublicationOperation operation) {
        if (!handles(operation)) return List.of();
        var record = mapper.byPublication(operation.id());
        if (record == null) return List.of("DIGEST_SNAPSHOT_MISSING");
        if (!runtime.live() || !properties.isEnabled() || !properties.problems().isEmpty()) return List.of("DIGEST_DISABLED");
        if (operation.synthetic() || !"SEND".equals(operation.kind()) || operation.eventVersion() != 1
                || operation.originalOperationId() != null || !record.target().equals(operation.target())
                || !record.target().equals(telegram.getChatId()) || !record.eventKey().equals(operation.eventKey())
                || !record.publicationId().equals(operation.id()) || !record.body().equals(operation.body())) {
            return List.of("DIGEST_OPERATION_CONTRACT_MISMATCH");
        }
        var now = clock.instant();
        if (!record.digestDate().equals(now.atZone(ZoneOffset.UTC).toLocalDate().toString())
                || record.windowEnd() > now.toEpochMilli() || record.windowEnd() < now.minus(Duration.ofHours(24)).toEpochMilli()
                || record.windowStart() != record.windowEnd() - Duration.ofHours(24).toMillis()) return List.of("DIGEST_EXPIRED");
        try {
            List<DigestItem> items = Arrays.asList(json.readValue(record.itemsPayload(), DigestItem[].class));
            List<String> ids = Arrays.asList(json.readValue(record.operationIdsPayload(), String[].class));
            if (items.isEmpty() || items.size() > properties.getMaxItems()
                    || !ids.equals(items.stream().map(DigestItem::operationId).toList())
                    || !DigestRenderer.eventKey(record.digestDate(), record.target()).equals(record.eventKey())
                    || !DigestRenderer.render(record.digestDate(), items).equals(record.body())
                    || DigestRenderer.length(record.body()) > properties.getMaxCharacters()) {
                return List.of("DIGEST_SNAPSHOT_INVALID");
            }
            return evidence.rejectionReasons(items, record.target(), Instant.ofEpochMilli(record.windowStart()),
                    Instant.ofEpochMilli(record.windowEnd()));
        } catch (RuntimeException invalidSnapshot) { return List.of("DIGEST_SNAPSHOT_INVALID"); }
    }
}
