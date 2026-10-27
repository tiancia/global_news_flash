package com.globalnewsflash.shared.port.out;

/** Encodes immutable workflow snapshots; the format is maintained by the persistence adapter. */
public interface SnapshotCodec {
    String writeValueAsString(Object value);
    <T> T readValue(String value, Class<T> type);
}
