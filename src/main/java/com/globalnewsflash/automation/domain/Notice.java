package com.globalnewsflash.automation.domain;

public record Notice(String id, String category, String subjectId, String reasonCode, String state,
                  int attempts, long nextAttemptAt, String lastError) {}
