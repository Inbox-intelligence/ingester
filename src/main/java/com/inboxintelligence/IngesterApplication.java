package com.inboxintelligence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IngesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngesterApplication.class, args);
    }
}
