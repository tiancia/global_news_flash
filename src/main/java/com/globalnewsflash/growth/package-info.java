/**
 * Synthetic promotion materials, authorized simulation targets, and invite attribution.
 *
 * <p>Start with {@link com.globalnewsflash.growth.application.GrowthService} for
 * target authorization, idempotent simulated distribution, invitation keys, and
 * known/unknown join attribution. Domain rules enforce synthetic identifiers and
 * prevent one distribution key from being reused with different content.</p>
 *
 * <p>This module owns the {@code growth_*} tables and is exercised by the offline
 * demonstration. It has no live promotion, invitation, subscriber, or reading
 * client. Its simulated counts are not evidence of audience growth, revenue, or
 * a real Telegram invitation being created.</p>
 */
package com.globalnewsflash.growth;
