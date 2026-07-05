package com.crash.model;

public record SimulationResult(
    int totalRounds,
    double winRatePct,
    double cashOutMultiplier,
    double maxCrashMultiplier,
    double avgCrashMultiplier,
    double medianCrashMultiplier,
    double totalStaked,
    double totalReturned,
    double rtp,
    double stdDev,
    double volatilityIndex,
    String volatilityLabel,
    long elapsedMs,
    String strategyWeights,
    String strategyMultipliers,
    long[] histogramCounts,
    double[] histogramEdges
) {}
