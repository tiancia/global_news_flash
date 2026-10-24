package com.globalnewsflash.growth.domain;

public record Distribution(String operationKey, String targetId, String body, String bodyHash,
                           String status, boolean synthetic, long createdAt) {}
