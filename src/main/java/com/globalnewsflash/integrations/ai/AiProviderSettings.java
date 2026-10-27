package com.globalnewsflash.integrations.ai;

import com.globalnewsflash.automation.port.out.RuntimeSettings;
import com.globalnewsflash.editorial.domain.EditorResponse;
import com.globalnewsflash.editorial.port.out.ModelPricing;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** Selects the active provider's readiness, request limit, and configured cost estimate. */
@Component
public class AiProviderSettings implements ModelPricing {
    private final RuntimeSettings runtime;
    private final OpenAiProperties openai;
    private final DeepSeekProperties deepseek;

    public AiProviderSettings(RuntimeSettings runtime, OpenAiProperties openai, DeepSeekProperties deepseek) {
        this.runtime = runtime;
        this.openai = openai;
        this.deepseek = deepseek;
    }

    private AiModelProperties selected() {
        return switch (runtime.getPreferredProvider()) {
            case "openai" -> openai;
            case "deepseek" -> deepseek;
            default -> throw new IllegalStateException("AI_PROVIDER_INVALID");
        };
    }

    public List<String> readinessReasons() {
        return List.of("openai", "deepseek").contains(runtime.getPreferredProvider())
                ? selected().readinessReasons() : List.of("AI_PROVIDER_INVALID");
    }
    public long estimatedRequestMicros() {
        return selected().estimatedRequestMicros();
    }
    public long actualMicros(EditorResponse.Usage usage) {
        return selected().actualMicros(usage);
    }
    public Duration getTimeout() {
        return selected().getTimeout();
    }
}
