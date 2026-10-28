/**
 * OpenAI and DeepSeek editor adapters, provider settings, and response contracts.
 *
 * <p>Start with {@link com.globalnewsflash.integrations.ai.OpenAiEditor} or
 * {@link com.globalnewsflash.integrations.ai.DeepSeekEditor} for requests and usage
 * parsing, and {@link com.globalnewsflash.integrations.ai.AiProviderSettings} for
 * configured readiness, request reservation estimates, and cost calculation.
 * Both adapters implement the editorial module's provider-neutral ports.</p>
 *
 * <p>Responses must match the structured statement/quote contract. Definite
 * rejections, invalid billable responses, and ambiguous transport results remain
 * distinct failures so {@code automation} can account for them appropriately.
 * This package neither reserves budget nor enqueues publication. Model identifiers
 * and configured prices must be maintained together; local estimates do not
 * establish the provider's actual charge.</p>
 */
package com.globalnewsflash.integrations.ai;
