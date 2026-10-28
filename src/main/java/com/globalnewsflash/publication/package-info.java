/**
 * Durable publication intent, delivery attempts, channel limits, and reconciliation.
 *
 * <p>Start with
 * {@link com.globalnewsflash.publication.application.PublicationService} for queue
 * processing and {@link com.globalnewsflash.publication.interfaces.PublicationController}
 * for protected preview, dispatch, and reconciliation endpoints. The service
 * persists {@code SENDING} and an attempt before calling
 * {@link com.globalnewsflash.publication.port.out.DeliveryGateway}; eligibility
 * guards recheck the immutable draft, evidence version, source admission, and mode.</p>
 *
 * <p>This module owns the {@code publication_*} tables, including the persisted
 * global pause, immutable operations, attempts, manual resolutions, alerts, and
 * per-channel cadence. A timeout, missing receipt, or expired sending lease becomes
 * {@code UNKNOWN} and never grants permission to resend automatically. Confirmed
 * non-delivery ends in {@code FAILED}; confirmed delivery retains the message ID
 * and audit. A local idempotency key cannot guarantee exactly-once remote delivery.</p>
 */
package com.globalnewsflash.publication;
