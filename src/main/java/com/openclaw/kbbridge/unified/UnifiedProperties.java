package com.openclaw.kbbridge.unified;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Data
@ConfigurationProperties("kb.unified")
public class UnifiedProperties {
    private String bucket = "kb-content";
    private String blogToken = "";
    private String vectorUrl = "";
    private String vectorToken = "";
    private int leaseSeconds = 180;
    private int maxAttempts = 8;
    private boolean workerEnabled = true;
}
