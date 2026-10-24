package com.globalnewsflash.automation.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
    @Bean
    Clock clock(@Value("${news.clock.fixed:}") String fixed) {
        return fixed.isBlank() ? Clock.systemUTC() : Clock.fixed(Instant.parse(fixed), ZoneOffset.UTC);
    }
}
