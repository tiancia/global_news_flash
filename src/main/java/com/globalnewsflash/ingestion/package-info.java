/**
 * Source admission, durable collection progress, and the editorial inbox.
 *
 * <p>Start with
 * {@link com.globalnewsflash.ingestion.application.LiveIngestionService} for source
 * synchronization, polling, inbox claims, and requeueing. Source declarations and
 * producer/license checks belong here; external fetch and parse implementations
 * live in {@code integrations.sources}. Other modules read admission through
 * {@link com.globalnewsflash.ingestion.port.out.SourceRegistry}.</p>
 *
 * <p>This module owns {@code news_source}, {@code ingestion_source_state}, and
 * {@code ingestion_inbox}. A frozen feed snapshot survives restarts; its page and
 * inbox rows commit together before its offset advances. Collection errors retain
 * the snapshot and retry the same page. Discovery-only items cannot become live
 * editorial evidence. Model execution and publication remain separate use cases.</p>
 */
package com.globalnewsflash.ingestion;
