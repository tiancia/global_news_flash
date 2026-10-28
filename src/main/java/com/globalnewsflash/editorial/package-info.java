/**
 * Evidence eligibility, structured draft validation, and editorial decisions.
 *
 * <p>Start with
 * {@link com.globalnewsflash.editorial.application.EditorialService} for the use
 * case and {@link com.globalnewsflash.editorial.domain.EditorialRules} for source,
 * freshness, topic, quote, number, and risk checks. The same rules revalidate saved
 * drafts before publication without another model call.</p>
 *
 * <p>Model requests cross
 * {@link com.globalnewsflash.editorial.port.out.EditorPort}; configured cost
 * estimates cross {@link com.globalnewsflash.editorial.port.out.ModelPricing}.
 * Provider protocols belong in {@code integrations.ai}; durable jobs, budget
 * reservations, and event snapshots belong in {@code automation}. This module
 * owns no database tables. Quote and number checks do not establish semantic
 * correctness or independent fact verification.</p>
 */
package com.globalnewsflash.editorial;
