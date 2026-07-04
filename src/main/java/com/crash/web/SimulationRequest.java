package com.crash.web;

import lombok.Data;

@Data
public class SimulationRequest {
    private int spins;
    private int threadCount;
    private double stake;
    private double multiplierMin;
    private double multiplierMid1;
    private double multiplierMid2;
    private double multiplierMid3;
    private double multiplierMax;
    private double crashExponentLow;
    private double crashExponentMid;
    private double crashExponentUpperMid;
    private double crashExponentHigh;
    private int zeroSpinInterval;
}
