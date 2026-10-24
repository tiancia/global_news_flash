package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.automation.application.DemoService;
import com.globalnewsflash.publication.application.PublicationService;
import org.springframework.core.env.Environment;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "news.demo.enabled", havingValue = "true")
public class DemoRunner implements ApplicationRunner {
    private final DemoService demo;
    private final JsonMapper json;
    private final PublicationService publication;
    private final Environment environment;
    public DemoRunner(DemoService demo, JsonMapper json, PublicationService publication, Environment environment) {
        this.demo = demo; this.json = json; this.publication = publication; this.environment = environment;
    }
    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type"))
                || environment.getProperty("news.publication.worker-enabled", Boolean.class, false)) {
            throw new IllegalStateException("CLI demo requires non-web mode with the worker disabled");
        }
        publication.setPaused(false);
        try {
            System.out.println("SYNTHETIC_DEMO_RESULT=" + json.writeValueAsString(demo.run(DemoService.Scenario.VALID)));
        } finally { publication.setPaused(true); }
    }
}
