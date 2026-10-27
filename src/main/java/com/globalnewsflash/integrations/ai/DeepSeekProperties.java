package com.globalnewsflash.integrations.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("news.deepseek")
public class DeepSeekProperties extends AiModelProperties {
    public DeepSeekProperties() {
        super("DEEPSEEK", "https://api.deepseek.com", "/chat/completions");
    }
}
