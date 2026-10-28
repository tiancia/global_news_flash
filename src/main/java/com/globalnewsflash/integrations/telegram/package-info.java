/**
 * Plain-text Telegram Bot API delivery and target/permission verification.
 *
 * <p>Start with
 * {@link com.globalnewsflash.integrations.telegram.TelegramDeliveryGateway} for
 * bot identity, channel identity, administrator permission, send/edit requests,
 * and receipt validation.
 * {@link com.globalnewsflash.integrations.telegram.TelegramPublicationGuard}
 * checks runtime switches, the configured target, content mode, and text limits.</p>
 *
 * <p>A validated success includes the expected channel and message ID; 429 carries
 * a bounded retry delay, while ambiguous responses return {@code UNKNOWN}.
 * Persistent attempts, retries, pause, and manual reconciliation belong to
 * {@code publication}. This package never authorizes a repeat send on its own and
 * never exposes bot tokens, request URLs, or raw response bodies in error codes.</p>
 */
package com.globalnewsflash.integrations.telegram;
