package com.crash.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "slot")
public class SimulationConfig {
    private int spins;
    private int threadCount;
    private double stake;
    private double cashOutMultiplier;
    private double multiplierMax;
    private int instantCrashInterval;
}
