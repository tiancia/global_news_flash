package com.globalnewsflash.editorial.domain;

import java.math.BigInteger;

/** Explicit micro-USD prices, conservative byte bounds and round-up billing estimates. */
public record ModelCost(String codePrefix, int maxInputCharacters, int maxOutputTokens,
        long inputMicrosPerMillionTokens, long outputMicrosPerMillionTokens) {
    private String code(String suffix) { return codePrefix + "_" + suffix; }

    public long estimatedRequestMicros() {
        if (maxInputCharacters < 0 || maxOutputTokens < 0 || inputMicrosPerMillionTokens <= 0
                || outputMicrosPerMillionTokens <= 0) throw new EditorFailure(code("PRICES_NOT_CONFIGURED"), false);
        long maximumInputTokens = Math.addExact(Math.multiplyExact((long) maxInputCharacters, 4L), 65_536L);
        return price(maximumInputTokens, maxOutputTokens);
    }

    public long actualMicros(EditorResponse.Usage usage) {
        if (usage == null || usage.estimated()) throw new EditorFailure(code("USAGE_UNKNOWN"), true);
        return price(usage.inputTokens(), usage.outputTokens());
    }

    private long price(long inputTokens, long outputTokens) {
        if (inputMicrosPerMillionTokens <= 0 || outputMicrosPerMillionTokens <= 0) {
            throw new EditorFailure(code("PRICES_NOT_CONFIGURED"), false);
        }
        BigInteger numerator = BigInteger.valueOf(inputTokens).multiply(BigInteger.valueOf(inputMicrosPerMillionTokens))
                .add(BigInteger.valueOf(outputTokens).multiply(BigInteger.valueOf(outputMicrosPerMillionTokens)));
        return numerator.add(BigInteger.valueOf(999_999L)).divide(BigInteger.valueOf(1_000_000L)).longValueExact();
    }
}
