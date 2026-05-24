package com.openclaw.kbbridge;

import com.openclaw.kbbridge.config.DotenvPropertyLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class KbBridgeApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(KbBridgeApplication.class);
        application.setDefaultProperties(DotenvPropertyLoader.load());
        application.run(args);
    }
}
