package com.globalnewsflash.publication.infrastructure;

import com.globalnewsflash.publication.port.in.PublicationUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Optional local simulation worker. It never unpauses publication or enables real delivery. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "news.publication.worker-enabled", havingValue = "true")
public class PublicationWorkerConfiguration {
    @Bean
    PublicationWorker publicationWorker(PublicationUseCase service) {
        return new PublicationWorker(service);
    }

    public static final class PublicationWorker {
        private final PublicationUseCase service;

        PublicationWorker(PublicationUseCase service) {
            this.service = service;
        }

        @Scheduled(fixedDelayString = "${news.publication.worker-delay-ms:5000}")
        public void tick() {
            service.processDueBatch();
        }
    }
}
