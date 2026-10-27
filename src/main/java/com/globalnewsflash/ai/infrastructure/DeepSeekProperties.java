package com.globalnewsflash.ai.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("news.deepseek")
public class DeepSeekProperties extends AiModelProperties {
    public DeepSeekProperties() {
        super("DEEPSEEK", "https://api.deepseek.com", "/chat/completions");
    }
}
