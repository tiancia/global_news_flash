package com.globalnewsflash.digest.application;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.digest.domain.DigestRenderer;
import com.globalnewsflash.digest.infrastructure.DigestMapper;
import com.globalnewsflash.digest.infrastructure.DigestMapper.DigestItem;
import com.globalnewsflash.digest.infrastructure.DigestMapper.DigestRecord;
import com.globalnewsflash.digest.infrastructure.DigestProperties;
import com.globalnewsflash.publication.application.PublicationService;
import com.globalnewsflash.publication.infrastructure.PublicationMapper;
import com.globalnewsflash.publication.infrastructure.TelegramProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The digest is a deterministic compilation of complete published briefs, with no new AI spend. */
@Service
public class DigestService {
    private final DigestProperties properties;
    private final DigestMapper mapper;
    private final PublicationMapper publicationMapper;
    private final PublicationService publications;
    private final DigestEvidenceValidator evidence;
    private final RuntimeProperties runtime;
    private final TelegramProperties telegram;
    private final Clock clock;
    private final JsonMapper json;

    public DigestService(DigestProperties properties, DigestMapper mapper, PublicationMapper publicationMapper,
            PublicationService publications, DigestEvidenceValidator evidence, RuntimeProperties runtime,
            TelegramProperties telegram, Clock clock, JsonMapper json) {
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
        if (utc.getHour() < properties.getUtcHour()) return Map.of("state", "NOT_DUE");
        // This existing singleton lock serializes the empty date key as well as concurrent pause changes.
        if (publicationMapper.lockPaused()) return Map.of("state", "PAUSED");
        String date = utc.toLocalDate().toString();
        var existing = mapper.lockDate(date, target);
        if (existing != null) return Map.of("state", "EXISTING", "digestId", existing.id(),
                "publicationId", existing.publicationId());
        var start = now.minus(Duration.ofHours(24));
        var latestByEvent = new LinkedHashMap<String, DigestMapper.Candidate>();
        for (var candidate : mapper.candidates(target, start, now)) {
            var prior = latestByEvent.get(candidate.eventKey());
            if (prior == null || prior.eventVersion() < candidate.eventVersion()) latestByEvent.put(candidate.eventKey(), candidate);
        }
        var candidates = new ArrayList<DigestItem>();
        latestByEvent.values().stream().sorted(Comparator.comparing(DigestMapper.Candidate::sentAt).reversed()
                .thenComparing(DigestMapper.Candidate::eventKey)).forEach(candidate -> {
            var operation = publicationMapper.find(candidate.operationId());
            if (operation != null) candidates.add(new DigestItem(operation.id(), operation.eventKey(), operation.eventVersion(),
                    operation.body(), candidate.sentAt()));
        });
        var eligible = evidence.eligibleItems(candidates, target, start, now);
        var selected = new ArrayList<DigestItem>();
        for (var item : eligible) {
            if (selected.size() >= properties.getMaxItems()) break;
            var expanded = new ArrayList<>(selected); expanded.add(item);
            if (DigestRenderer.length(DigestRenderer.render(date, expanded)) <= properties.getMaxCharacters()) selected.add(item);
        }
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
