package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.automation.port.out.DemoFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public final class ClasspathDemoFixtures implements DemoFixtures {
    public String load(String file) {
        try (var stream = new ClassPathResource("fixtures/" + file).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Local fixture unavailable", failure); }
    }
}
