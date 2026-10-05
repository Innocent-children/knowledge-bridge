package com.openclaw.kbbridge;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BridgeProperties.class)
public class KnowledgeBridgeApplication {
    public static void main(String[] args) { SpringApplication.run(KnowledgeBridgeApplication.class,args); }
}
