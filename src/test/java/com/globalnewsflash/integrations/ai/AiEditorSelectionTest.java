package com.globalnewsflash.integrations.ai;

import com.globalnewsflash.editorial.application.EditorPort;
import com.globalnewsflash.editorial.infrastructure.SyntheticEditor;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class AiEditorSelectionTest {
    @Test void offlineSelectsOnlySynthetic() { check(Map.of(), SyntheticEditor.class); }
    @Test void liveDefaultsToOpenAi() { check(Map.of("news.runtime.mode", "LIVE"), OpenAiEditor.class); }
    @Test void liveDeepSeekSelectsOnlyDeepSeek() {
        check(Map.of("news.runtime.mode", "LIVE", "news.runtime.preferred-provider", "deepseek"), DeepSeekEditor.class);
    }
    private void check(Map<String, Object> properties, Class<?> expected) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.registerBean(OpenAiProperties.class, OpenAiProperties::new);
            context.registerBean(DeepSeekProperties.class, DeepSeekProperties::new);
            context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
            context.register(SyntheticEditor.class, OpenAiEditor.class, DeepSeekEditor.class);
            context.refresh();
            assertThat(context.getBeansOfType(EditorPort.class).values()).hasSize(1).first().isInstanceOf(expected);
        }
    }
}
