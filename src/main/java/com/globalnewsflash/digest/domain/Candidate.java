package com.globalnewsflash.digest.domain;

import java.time.Instant;

public record Candidate(String operationId, String eventKey, int eventVersion, Instant sentAt) {}
