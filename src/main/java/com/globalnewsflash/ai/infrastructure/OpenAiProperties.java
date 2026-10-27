package com.globalnewsflash.ai.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("news.openai")
public class OpenAiProperties extends AiModelProperties {
    public OpenAiProperties() {
        super("OPENAI", "https://api.openai.com/v1", "/responses");
    }
}
