package com.globalnewsflash.digest.infrastructure;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.digest.application.DigestService;
import com.globalnewsflash.publication.application.PublicationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Existing validated briefs can form a digest even while the AI provider is unconfigured. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "news.digest.enabled", havingValue = "true")
public class DigestScheduling {
    private final DigestService digests;
    private final PublicationService publications;
    private final RuntimeProperties runtime;

    public DigestScheduling(DigestService digests, PublicationService publications, RuntimeProperties runtime) {
        this.digests = digests; this.publications = publications; this.runtime = runtime;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void tick() {
        if (!runtime.live()) return;
        digests.runDue();
        publications.processDueBatch();
    }
}
