package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.ingestion.port.in.IngestionUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Registers explicit source configuration locally; startup never fetches a source or sends a message. */
@Component
public class LiveBootstrap implements ApplicationRunner {
    private final IngestionUseCase ingestion;
    public LiveBootstrap(IngestionUseCase ingestion) { this.ingestion = ingestion; }
    @Override public void run(ApplicationArguments arguments) { if (ingestion.active()) ingestion.syncSources(); }
}
