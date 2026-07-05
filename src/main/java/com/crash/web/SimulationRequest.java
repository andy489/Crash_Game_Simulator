package com.crash.web;

import lombok.Data;

@Data
public class SimulationRequest {
    private int spins;
    private int threadCount;
    private double stake;
    private double cashOutMultiplier;
    private double multiplierMax;
    private int instantCrashInterval;
    private String strategyWeights = "";
    private String strategyMultipliers = "";
}
