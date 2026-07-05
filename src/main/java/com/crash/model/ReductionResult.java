package com.crash.model;

public record ReductionResult(
    int threadId,
    int spins,
    int wins,
    double totalStaked,
    double totalReturned,
    double maxMultiplier,
    double avgCrashMultiplier,
    double medianCrashMultiplier,
    double p90CrashMultiplier,
    double sumSquaredCrash,
    long[] histogramCounts
) {}
