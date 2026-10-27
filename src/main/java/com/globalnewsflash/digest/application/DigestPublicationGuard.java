package com.globalnewsflash.digest.application;

import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.digest.domain.DigestItem;
import com.globalnewsflash.digest.domain.DigestPublicationPolicy;
import com.globalnewsflash.digest.port.in.DigestEvidenceUseCase;
import com.globalnewsflash.digest.port.in.DigestGuard;
import com.globalnewsflash.digest.port.out.DigestRepository;
import com.globalnewsflash.digest.port.out.DigestSettings;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.port.out.PublicationGuard;
import com.globalnewsflash.publication.port.out.PublicationTarget;
import com.globalnewsflash.shared.port.out.SnapshotCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/** Digest routing is only a lookup hint; all authority comes from the saved snapshot and its evidence. */
@Component
public final class DigestPublicationGuard implements PublicationGuard, DigestGuard {
    private final DigestRepository mapper;
    private final DigestSettings properties;
    private final DigestEvidenceUseCase evidence;
    private final RuntimeSettings runtime;
    private final PublicationTarget telegram;
    private final SnapshotCodec json;
    private final Clock clock;

    public DigestPublicationGuard(DigestRepository mapper, DigestSettings properties, DigestEvidenceUseCase evidence,
            RuntimeSettings runtime, PublicationTarget telegram, SnapshotCodec json, Clock clock) {
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
        if (!DigestPublicationPolicy.matches(operation, record, telegram.getChatId())) return List.of("DIGEST_OPERATION_CONTRACT_MISMATCH");
        if (DigestPublicationPolicy.expired(record, clock.instant())) return List.of("DIGEST_EXPIRED");
        try {
            List<DigestItem> items = Arrays.asList(json.readValue(record.itemsPayload(), DigestItem[].class));
            List<String> ids = Arrays.asList(json.readValue(record.operationIdsPayload(), String[].class));
            if (!DigestPublicationPolicy.validSnapshot(record, items, ids, properties.getMaxItems(), properties.getMaxCharacters()))
                return List.of("DIGEST_SNAPSHOT_INVALID");
            return evidence.rejectionReasons(items, record.target(), Instant.ofEpochMilli(record.windowStart()),
                    Instant.ofEpochMilli(record.windowEnd()));
        } catch (RuntimeException invalidSnapshot) { return List.of("DIGEST_SNAPSHOT_INVALID"); }
    }
}
