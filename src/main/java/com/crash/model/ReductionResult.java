package com.crash.model;

public record ReductionResult(
    int threadId,
    int spins,
    int wins,
    int hitRate,
    double totalStaked,
    double totalReturned,
    double maxMultiplier,
    double avgMultiplier,
    double medianMultiplier
) {}
