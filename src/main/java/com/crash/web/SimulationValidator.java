package com.crash.web;

import java.util.ArrayList;
import java.util.List;

public class SimulationValidator {

    private static final int MAX_SPINS = 1_000_000_000;
    private static final int MAX_THREADS = 8;
    private static final double MAX_STAKE = 200.0;

    public static List<String> validate(SimulationRequest r) {
        List<String> errors = new ArrayList<>();

        if (r.getSpins() < 1 || r.getSpins() > MAX_SPINS)
            errors.add("Rounds must be between 1 and 1,000,000,000.");
        if (r.getThreadCount() < 1 || r.getThreadCount() > MAX_THREADS)
            errors.add("Thread count must be between 1 and 8.");
        if (r.getStake() <= 0 || r.getStake() > MAX_STAKE)
            errors.add("Stake must be between 0.1 and 200.0.");
        if (!isDivisibleBy0_1(r.getStake()))
            errors.add("Stake must be divisible by 0.1.");

        double max = r.getMultiplierMax();
        if (max < 10.0 || max > 100_000.0)
            errors.add("Max multiplier must be between 10 and 100,000.");
        if (!isDivisibleBy0_1(max))
            errors.add("Max multiplier must be divisible by 0.1.");

        if (r.getInstantCrashInterval() < 0)
            errors.add("Instant crash interval must be 0 or greater.");

        boolean isStrategy = r.getStrategyWeights() != null && !r.getStrategyWeights().isBlank();
        if (isStrategy) {
            validateStrategy(r, errors);
        } else {
            double cashOut = r.getCashOutMultiplier();
            if (cashOut < 1.01)
                errors.add("Cash-out multiplier must be at least 1.01.");
            if (!isDivisibleBy0_01(cashOut))
                errors.add("Cash-out multiplier must be divisible by 0.01.");
            if (max <= cashOut)
                errors.add("Max multiplier must be greater than cash-out multiplier.");
        }

        return errors;
    }

    private static void validateStrategy(SimulationRequest r, List<String> errors) {
        String[] wParts = r.getStrategyWeights().split(",");
        String mStr = r.getStrategyMultipliers() == null ? "" : r.getStrategyMultipliers();
        String[] mParts = mStr.split(",");

        if (wParts.length < 2) {
            errors.add("Strategy requires at least 2 entries.");
            return;
        }
        if (wParts.length != mParts.length) {
            errors.add("Strategy weights and multipliers must have the same number of entries.");
            return;
        }

        double weightSum = 0;
        double maxMult = 0;
        for (int i = 0; i < wParts.length; i++) {
            double w, m;
            try { w = Double.parseDouble(wParts[i].trim()); }
            catch (NumberFormatException e) { errors.add("Invalid weight at position " + (i + 1) + "."); return; }
            try { m = Double.parseDouble(mParts[i].trim().replace("x", "")); }
            catch (NumberFormatException e) { errors.add("Invalid multiplier at position " + (i + 1) + "."); return; }

            if (w <= 0) errors.add("Weight at position " + (i + 1) + " must be greater than 0.");
            if (m < 1.01) errors.add("Multiplier at position " + (i + 1) + " must be at least 1.01.");
            if (!isDivisibleBy0_01(m)) errors.add("Multiplier at position " + (i + 1) + " must be divisible by 0.01.");
            weightSum += w;
            if (m > maxMult) maxMult = m;
        }

        if (Math.abs(weightSum - 100.0) > 0.001)
            errors.add("Strategy weights must sum to 100 (currently " + String.format("%.4f", weightSum) + ").");

        if (errors.isEmpty() && r.getMultiplierMax() <= maxMult)
            errors.add("Max multiplier must be greater than all strategy multipliers (max strategy: " + maxMult + ").");
    }

    private static boolean isDivisibleBy0_1(double value) {
        return Math.abs(Math.round(value * 10) - value * 10) < 1e-9;
    }

    private static boolean isDivisibleBy0_01(double value) {
        return Math.abs(Math.round(value * 100) - value * 100) < 1e-7;
    }
}
