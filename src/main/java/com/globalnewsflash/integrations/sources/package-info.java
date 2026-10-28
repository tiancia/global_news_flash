/**
 * Bounded HTTPS source collection and conversion into attributed material.
 *
 * <p>Start with
 * {@link com.globalnewsflash.integrations.sources.ConfiguredSourceCollector} for
 * lazy adapter construction and
 * {@link com.globalnewsflash.integrations.sources.LiveSourceCollector} for frozen
 * feed pages, Fed article bodies, RSS/Atom, USGS, and GDELT. Transport enforces
 * exact host allowlists, public addresses, response limits, and no redirects.</p>
 *
 * <p>This package owns no database state: {@code ingestion} persists snapshots and
 * inbox progress through its collection port. A failed article aborts the current
 * page; there is currently no per-article quarantine in the collector. GDELT is
 * discovery only, and RSS summaries or extracted source passages do not constitute
 * independent fact verification or publication permission.</p>
 */
package com.globalnewsflash.integrations.sources;
