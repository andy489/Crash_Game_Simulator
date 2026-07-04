package com.crash.model;

public record SimulationResult(
    int totalSpins,
    double hitRatePct,
    double winRatePct,
    double maxMultiplier,
    double avgMultiplier,
    double medianMultiplier,
    double totalStaked,
    double totalReturned,
    double rtp,
    long elapsedMs
) {}
