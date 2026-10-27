package com.globalnewsflash.shared.infrastructure;

import com.globalnewsflash.shared.port.out.SnapshotCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class JacksonSnapshotCodec implements SnapshotCodec {
    private final JsonMapper json;
    public JacksonSnapshotCodec(JsonMapper json) { this.json = json; }
    public String writeValueAsString(Object value) { return json.writeValueAsString(value); }
    public <T> T readValue(String value, Class<T> type) { return json.readValue(value, type); }
}
