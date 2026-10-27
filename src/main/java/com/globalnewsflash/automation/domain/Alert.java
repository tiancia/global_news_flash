package com.globalnewsflash.automation.domain;

public record Alert(String id, String category, String subjectId, String reasonCode, long createdAt) {}
