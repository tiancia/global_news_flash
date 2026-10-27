package com.globalnewsflash.integrations.ai;

import com.globalnewsflash.editorial.domain.EditorFailure;
import com.globalnewsflash.editorial.domain.EditorResponse;
import com.globalnewsflash.editorial.domain.ModelCost;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** All secrets are supplied externally. Prices are explicit operator inputs in USD millionths. */
public abstract class AiModelProperties {
    private final String codePrefix;
    private final String endpointPath;
    private String apiKey = "";
    private String model = "";
    private String baseUrl;
    private Duration timeout = Duration.ofSeconds(45);
    private int maxInputCharacters = 24_000;
    private int maxOutputTokens = 1_800;
    private long inputMicrosPerMillionTokens;
    private long outputMicrosPerMillionTokens;

    protected AiModelProperties(String codePrefix, String baseUrl, String endpointPath) {
        this.codePrefix = codePrefix;
        this.baseUrl = baseUrl;
        this.endpointPath = endpointPath;
    }

    String code(String suffix) { return codePrefix + "_" + suffix; }

    public List<String> readinessReasons() {
        var reasons = new ArrayList<String>();
        if (apiKey == null || apiKey.isBlank() || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            reasons.add(code("KEY_MISSING_OR_INVALID"));
        }
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) reasons.add(code("MODEL_NOT_CONFIGURED"));
        try { endpoint(); } catch (RuntimeException invalid) { reasons.add(code("ENDPOINT_INVALID")); }
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(3)) > 0) reasons.add(code("TIMEOUT_INVALID"));
        if (maxInputCharacters < 1_000 || maxInputCharacters > 100_000
                || maxOutputTokens < 256 || maxOutputTokens > 16_000) reasons.add(code("TOKEN_LIMIT_INVALID"));
        if (inputMicrosPerMillionTokens <= 0 || outputMicrosPerMillionTokens <= 0) reasons.add(code("PRICES_NOT_CONFIGURED"));
        return List.copyOf(reasons);
    }

    public void validateForCall() {
        var reasons = readinessReasons();
        if (!reasons.isEmpty()) throw new EditorFailure(reasons.getFirst(), false);
    }

    public URI endpoint() {
        URI base = URI.create(baseUrl == null ? "" : baseUrl);
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || (base.getPort() != -1 && base.getPort() != 443)) {
            throw new IllegalArgumentException("Invalid AI endpoint");
        }
        return URI.create(base.toASCIIString().replaceAll("/+$", "") + endpointPath);
    }

    /** One token per UTF-8 byte is deliberately conservative; overhead also covers prompt/schema. */
    public long estimatedRequestMicros() { return costs().estimatedRequestMicros(); }

    public long actualMicros(EditorResponse.Usage usage) { return costs().actualMicros(usage); }

    private ModelCost costs() {
        return new ModelCost(codePrefix, maxInputCharacters, maxOutputTokens, inputMicrosPerMillionTokens, outputMicrosPerMillionTokens);
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public int getMaxInputCharacters() { return maxInputCharacters; }
    public void setMaxInputCharacters(int value) { maxInputCharacters = value; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = value; }
    public long getInputMicrosPerMillionTokens() { return inputMicrosPerMillionTokens; }
    public void setInputMicrosPerMillionTokens(long value) { inputMicrosPerMillionTokens = value; }
    public long getOutputMicrosPerMillionTokens() { return outputMicrosPerMillionTokens; }
    public void setOutputMicrosPerMillionTokens(long value) { outputMicrosPerMillionTokens = value; }
}
