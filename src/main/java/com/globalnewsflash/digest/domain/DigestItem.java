package com.globalnewsflash.digest.domain;

import java.time.Instant;

public record DigestItem(String operationId, String eventKey, int eventVersion, String body, Instant sentAt) {}
