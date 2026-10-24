package com.globalnewsflash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GlobalNewsFlashApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(GlobalNewsFlashApplication.class, args);
        if (context.getEnvironment().getProperty("news.demo.enabled", Boolean.class, false)
                && "none".equalsIgnoreCase(context.getEnvironment().getProperty("spring.main.web-application-type"))) {
            context.close();
        }
    }
}
