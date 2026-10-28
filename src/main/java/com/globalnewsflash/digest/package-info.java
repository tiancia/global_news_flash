/**
 * Deterministic daily digests compiled from previously confirmed live publications.
 *
 * <p>Start with {@link com.globalnewsflash.digest.application.DigestService} for
 * the due-window and enqueue use case, and
 * {@link com.globalnewsflash.digest.application.DigestEvidenceValidator} for
 * current evidence eligibility. Domain policies select complete briefs and enforce
 * time, item, length, and immutable snapshot constraints. No new AI request is made.</p>
 *
 * <p>This module owns {@code news_digest}; the date/target snapshot and its
 * publication intent commit in one transaction. Delivery and pause checks remain
 * in {@code publication}, and digest guards revalidate component news before
 * dispatch. The feature is optional and disabled by default, including in the
 * live profile.</p>
 */
package com.globalnewsflash.digest;
