package com.globalnewsflash.growth.domain;

public record Target(String targetId, String label, boolean synthetic, boolean authorized, long createdAt, long updatedAt) {}
