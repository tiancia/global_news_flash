package com.globalnewsflash.growth.domain;

public record Invite(String inviteKey, String targetId, boolean synthetic, long createdAt) {}
