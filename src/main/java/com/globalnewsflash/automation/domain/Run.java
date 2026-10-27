package com.globalnewsflash.automation.domain;

public record Run(String id, long startedAt, Long finishedAt, String state, int processed, String lastError) {}
