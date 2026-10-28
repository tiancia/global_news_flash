/**
 * Shared bounded HTTP response consumption for external adapters.
 *
 * <p>Start with
 * {@link com.globalnewsflash.integrations.http.BoundedBodySubscriber} for byte
 * limits, overflow cancellation, and complete-body delivery. Provider adapters
 * choose their own sanitized failure codes and request timeouts.</p>
 *
 * <p>This package has no business workflow, credentials, database state, or retry
 * policy. Collection admission, billable uncertainty, and publication outcomes
 * remain decisions of the calling adapter and its owning business use case.</p>
 */
package com.globalnewsflash.integrations.http;
