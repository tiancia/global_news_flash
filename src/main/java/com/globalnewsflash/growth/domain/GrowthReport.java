package com.globalnewsflash.growth.domain;

/** Counts describe the supplied offline events, not Telegram-wide readership or conversion. */
public record GrowthReport(long knownJoins, long unknownJoins, long distributions, boolean synthetic) {}
