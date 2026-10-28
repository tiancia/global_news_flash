/**
 * Small cross-module primitives and snapshot serialization contracts.
 *
 * <p>{@link com.globalnewsflash.shared.Hashing} supplies deterministic hashing.
 * {@link com.globalnewsflash.shared.port.out.SnapshotCodec} isolates application
 * services from the JSON library, with
 * {@link com.globalnewsflash.shared.infrastructure.JacksonSnapshotCodec} as its
 * adapter.</p>
 *
 * <p>Keep module-specific rules, configuration, state machines, and external
 * clients in their owning modules. Pure domain code may use {@code Hashing} but
 * must not depend on the infrastructure codec or perform persistence through it.</p>
 */
package com.globalnewsflash.shared;
