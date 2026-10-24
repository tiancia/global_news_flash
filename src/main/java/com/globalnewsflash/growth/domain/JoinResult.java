package com.globalnewsflash.growth.domain;

public record JoinResult(long updateId, String inviteKey, String targetId, String attribution,
                         String reason, boolean synthetic, long createdAt) {}
