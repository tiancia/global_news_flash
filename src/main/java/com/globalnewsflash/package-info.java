/**
 * Independent news-to-Telegram backend, organized by business module and then layer.
 *
 * <p>Start with {@link com.globalnewsflash.GlobalNewsFlashApplication} for process
 * startup and each module's package comment for its use cases. HTTP and scheduled
 * entry points call inbound ports; application services coordinate domain rules
 * and outbound ports; infrastructure and integrations supply the adapters.</p>
 *
 * <p>The live path collects admitted source material, validates evidence, reserves
 * model budget, edits a draft, and persists a publication intent before delivery.
 * Runtime mode, external-access switches, source admission, and the persisted
 * publication pause are separate controls. Offline demonstrations use synthetic
 * material and simulated delivery.</p>
 */
package com.globalnewsflash;
