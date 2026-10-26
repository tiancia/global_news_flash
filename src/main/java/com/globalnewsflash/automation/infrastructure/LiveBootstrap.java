package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.ingestion.application.LiveIngestionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Registers explicit source configuration locally; startup never fetches a source or sends a message. */
@Component
public class LiveBootstrap implements ApplicationRunner {
    private final LiveIngestionService ingestion;
    public LiveBootstrap(LiveIngestionService ingestion) { this.ingestion = ingestion; }
    @Override public void run(ApplicationArguments arguments) { if (ingestion.active()) ingestion.syncSources(); }
}
