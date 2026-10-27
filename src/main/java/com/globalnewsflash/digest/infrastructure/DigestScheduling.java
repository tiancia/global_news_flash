package com.globalnewsflash.digest.infrastructure;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.digest.port.in.DigestUseCase;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Existing validated briefs can form a digest even while the AI provider is unconfigured. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "news.digest.enabled", havingValue = "true")
public class DigestScheduling {
    private final DigestUseCase digests;
    private final PublicationUseCase publications;
    private final RuntimeProperties runtime;

    public DigestScheduling(DigestUseCase digests, PublicationUseCase publications, RuntimeProperties runtime) {
        this.digests = digests; this.publications = publications; this.runtime = runtime;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void tick() {
        if (!runtime.live()) return;
        digests.runDue();
        publications.processDueBatch();
    }
}
