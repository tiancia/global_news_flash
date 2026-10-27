package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.automation.domain.DemoScenario;
import com.globalnewsflash.automation.port.in.DemoUseCase;
import com.globalnewsflash.publication.port.in.PublicationUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "news.demo.enabled", havingValue = "true")
public class DemoRunner implements ApplicationRunner {
    private final DemoUseCase demo;
    private final JsonMapper json;
    private final PublicationUseCase publication;
    private final Environment environment;
    public DemoRunner(DemoUseCase demo, JsonMapper json, PublicationUseCase publication, Environment environment) {
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
            System.out.println("SYNTHETIC_DEMO_RESULT=" + json.writeValueAsString(demo.run(DemoScenario.VALID)));
        } finally { publication.setPaused(true); }
    }
}
