package com.globalnewsflash.digest.application;

import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.digest.domain.DigestItem;
import com.globalnewsflash.digest.domain.DigestPolicy;
import com.globalnewsflash.digest.domain.DigestRecord;
import com.globalnewsflash.digest.domain.DigestRenderer;
import com.globalnewsflash.digest.port.in.DigestEvidenceUseCase;
import com.globalnewsflash.digest.port.in.DigestUseCase;
import com.globalnewsflash.digest.port.out.DigestRepository;
import com.globalnewsflash.digest.port.out.DigestSettings;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import com.globalnewsflash.publication.port.out.PublicationRepository;
import com.globalnewsflash.publication.port.out.PublicationTarget;
import com.globalnewsflash.shared.port.out.SnapshotCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** The digest is a deterministic compilation of complete published briefs, with no new AI spend. */
@Service
public class DigestService implements DigestUseCase {
    private final DigestSettings properties;
    private final DigestRepository mapper;
    private final PublicationRepository publicationMapper;
    private final PublicationUseCase publications;
    private final DigestEvidenceUseCase evidence;
    private final RuntimeSettings runtime;
    private final PublicationTarget telegram;
    private final Clock clock;
    private final SnapshotCodec json;

    public DigestService(DigestSettings properties, DigestRepository mapper, PublicationRepository publicationMapper,
            PublicationUseCase publications, DigestEvidenceUseCase evidence, RuntimeSettings runtime,
            PublicationTarget telegram, Clock clock, SnapshotCodec json) {
        this.properties = properties; this.mapper = mapper; this.publicationMapper = publicationMapper;
        this.publications = publications; this.evidence = evidence; this.runtime = runtime;
        this.telegram = telegram; this.clock = clock; this.json = json;
    }

    public List<DigestRecord> list() { return List.copyOf(mapper.list()); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> runDue() {
        if (!runtime.live() || !properties.isEnabled()) return Map.of("state", "DISABLED");
        if (!properties.problems().isEmpty()) return Map.of("state", "NOT_READY", "reasons", properties.problems());
        String target = telegram.getChatId();
        if (target == null || target.isBlank()) return Map.of("state", "NOT_READY", "reasons", List.of("TELEGRAM_TARGET_MISSING"));
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var utc = now.atZone(ZoneOffset.UTC);
        if (!DigestPolicy.due(now, properties.getUtcHour())) return Map.of("state", "NOT_DUE");
        // This existing singleton lock serializes the empty date key as well as concurrent pause changes.
        if (publicationMapper.lockPaused()) return Map.of("state", "PAUSED");
        String date = utc.toLocalDate().toString();
        var existing = mapper.lockDate(date, target);
        if (existing != null) return Map.of("state", "EXISTING", "digestId", existing.id(),
                "publicationId", existing.publicationId());
        var start = now.minus(Duration.ofHours(24));
        var candidates = new ArrayList<DigestItem>();
        for (var candidate : DigestPolicy.latestCandidates(mapper.candidates(target, start, now))) {
            var operation = publicationMapper.find(candidate.operationId());
            if (operation != null) candidates.add(new DigestItem(operation.id(), operation.eventKey(), operation.eventVersion(), operation.body(), candidate.sentAt()));
        }
        var eligible = evidence.eligibleItems(candidates, target, start, now);
        var selected = DigestPolicy.select(date, eligible, properties.getMaxItems(), properties.getMaxCharacters());
        // No durable key is consumed for an empty result; later news can still form today's digest.
        if (selected.isEmpty()) return Map.of("state", "EMPTY");
        String id = UUID.randomUUID().toString();
        String key = DigestRenderer.eventKey(date, target);
        String body = DigestRenderer.render(date, selected);
        mapper.insert(new DigestRecord(id, date, target, key, null, body,
                json.writeValueAsString(selected.stream().map(DigestItem::operationId).toList()),
                json.writeValueAsString(selected), start.toEpochMilli(), now.toEpochMilli(), now.toEpochMilli()));
        // enqueue joins this transaction: the immutable snapshot and its task commit together.
        var operation = publications.enqueue(key, 1, target, "SEND", body, null, false);
        if (mapper.attachPublication(id, operation.id()) != 1) throw new IllegalStateException("DIGEST_PUBLICATION_CONFLICT");
        return Map.of("state", "QUEUED", "digestId", id, "publicationId", operation.id(), "items", selected.size());
    }
}
