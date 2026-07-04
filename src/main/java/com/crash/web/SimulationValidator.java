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
            errors.add("Spins must be between 1 and 1,000,000,000.");
        if (r.getThreadCount() < 1 || r.getThreadCount() > MAX_THREADS)
            errors.add("Thread count must be between 1 and 8.");
        if (r.getStake() <= 0 || r.getStake() > MAX_STAKE)
            errors.add("Stake must be between 0.1 and 200.0.");
        if (!isDivisibleBy0_1(r.getStake()))
            errors.add("Stake must be divisible by 0.1.");
        if (r.getZeroSpinInterval() < 0)
            errors.add("Zero spin interval must be 0 or greater.");

        double mn = r.getMultiplierMin(), m1 = r.getMultiplierMid1(),
               m2 = r.getMultiplierMid2(), m3 = r.getMultiplierMid3(),
               mx = r.getMultiplierMax();

        if (!isDivisibleBy0_1(mn)) errors.add("Multiplier Min must be divisible by 0.1.");
        if (!isDivisibleBy0_1(m1)) errors.add("Multiplier Mid 1 must be divisible by 0.1.");
        if (!isDivisibleBy0_1(m2)) errors.add("Multiplier Mid 2 must be divisible by 0.1.");
        if (!isDivisibleBy0_1(m3)) errors.add("Multiplier Mid 3 must be divisible by 0.1.");
        if (!isDivisibleBy0_1(mx)) errors.add("Multiplier Max must be divisible by 0.1.");

        if (!(mn < m1 && m1 < m2 && m2 < m3 && m3 < mx))
            errors.add("Multiplier boundaries must satisfy: Min < Mid1 < Mid2 < Mid3 < Max.");

        if (r.getCrashExponentLow() <= 0) errors.add("Crash Exponent Low must be > 0.");
        if (r.getCrashExponentMid() <= 0) errors.add("Crash Exponent Mid must be > 0.");
        if (r.getCrashExponentUpperMid() <= 0) errors.add("Crash Exponent Upper Mid must be > 0.");
        if (r.getCrashExponentHigh() <= 0) errors.add("Crash Exponent High must be > 0.");

        return errors;
    }

    private static boolean isDivisibleBy0_1(double value) {
        return Math.abs(Math.round(value * 10) - value * 10) < 1e-9;
    }
}
