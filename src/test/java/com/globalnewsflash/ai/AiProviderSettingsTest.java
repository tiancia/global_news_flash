package com.globalnewsflash.ai;

import com.globalnewsflash.ai.application.AiProviderSettings;
import com.globalnewsflash.ai.infrastructure.DeepSeekProperties;
import com.globalnewsflash.ai.infrastructure.OpenAiProperties;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.editorial.domain.EditorResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

class AiProviderSettingsTest {
    @Test void inheritedPropertiesBindIndependentlyUnderExistingPrefixes() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "news.openai.api-key", "test-openai",
                "news.openai.model", "openai-test",
                "news.openai.timeout", "30s",
                "news.deepseek.api-key", "test-deepseek",
                "news.deepseek.model", "deepseek-test",
                "news.deepseek.timeout", "60s")));
        var openai = binder.bind("news.openai", Bindable.of(OpenAiProperties.class)).get();
        var deepseek = binder.bind("news.deepseek", Bindable.of(DeepSeekProperties.class)).get();
        assertThat(openai.getApiKey()).isEqualTo("test-openai");
        assertThat(deepseek.getApiKey()).isEqualTo("test-deepseek");
        assertThat(openai.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(deepseek.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(openai.endpoint().toString()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(deepseek.endpoint().toString()).isEqualTo("https://api.deepseek.com/chat/completions");
    }

    @Test void selectedProviderControlsReadinessTimeoutAndPricing() {
        var runtime = new RuntimeProperties();
        var openai = new OpenAiProperties();
        openai.setApiKey("test-openai");
        openai.setModel("openai-model");
        openai.setTimeout(Duration.ofSeconds(30));
        openai.setInputMicrosPerMillionTokens(100_000);
        openai.setOutputMicrosPerMillionTokens(500_000);
        var deepseek = new DeepSeekProperties();
        deepseek.setModel("deepseek-flash");
        deepseek.setTimeout(Duration.ofSeconds(60));
        deepseek.setInputMicrosPerMillionTokens(300_000);
        deepseek.setOutputMicrosPerMillionTokens(1_200_000);
        var settings = new AiProviderSettings(runtime, openai, deepseek);
        var usage = new EditorResponse.Usage(1_000_000, 1_000_000, false);
        assertThat(settings.readinessReasons()).isEmpty();
        assertThat(settings.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.actualMicros(usage)).isEqualTo(600_000);
        long openaiEstimate = settings.estimatedRequestMicros();
        runtime.setPreferredProvider("deepseek");
        assertThat(settings.readinessReasons()).contains("DEEPSEEK_KEY_MISSING_OR_INVALID");
        deepseek.setApiKey("test-deepseek");
        assertThat(settings.readinessReasons()).isEmpty();
        assertThat(settings.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.actualMicros(usage)).isEqualTo(1_500_000);
        assertThat(settings.estimatedRequestMicros()).isGreaterThan(openaiEstimate);
    }
}
