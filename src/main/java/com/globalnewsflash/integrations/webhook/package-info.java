/**
 * Optional outbound operational alert transport.
 *
 * <p>Start with
 * {@link com.globalnewsflash.integrations.webhook.WebhookNotificationGateway} for
 * endpoint validation, bounded HTTP delivery, and acknowledgement handling.
 * {@code AlertProperties} binds {@code news.alert}; the feature is disabled by
 * default. Notice selection, persistence, claims, and retries belong to
 * {@code automation.OpsNotificationService} and its outbound ports.</p>
 *
 * <p>Payloads contain stable notice identifiers and reason codes. Webhook delivery
 * is at least once: a receiver should deduplicate by notice ID. An alert receipt
 * does not reconcile model cost or resolve a Telegram publication.</p>
 */
package com.globalnewsflash.integrations.webhook;
