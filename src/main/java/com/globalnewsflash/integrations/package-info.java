/**
 * External adapters grouped by provider and capability.
 *
 * <p>{@code sources} implements collection and parsing, {@code ai} implements the
 * editor and pricing ports, {@code telegram} implements message transport and its
 * preflight guard, and {@code webhook} implements operational notice delivery.
 * {@code http} contains shared bounded response handling.</p>
 *
 * <p>Keep wire formats, authentication, timeouts, response limits, and sanitized
 * transport failures here. Source admission, editorial decisions, budget records,
 * queue state, and retry authorization remain owned by business modules. Adapter
 * construction or local configuration readiness does not verify remote access,
 * provider quota, or channel permission. Read each capability's package comment
 * before extending its transport.</p>
 */
package com.globalnewsflash.integrations;
