package com.crash.simulation;

import com.crash.web.SimulationRequest;

/**
 * Pure Pareto distribution over [1.0, multiplierMax].
 *
 * Survival function: S(x) = 1/x  (equivalently P(crash ≥ x) = 1/x)
 * Inverse-CDF:       x = 1 / (1 − u),  u ∈ [0, 1)
 *
 * This is the unique distribution where E[payout] = cashOut × S(cashOut)
 * = cashOut × (1/cashOut) = 1.0 for every cash-out multiplier,
 * guaranteeing exactly 100% theoretical RTP regardless of the target chosen.
 *
 * The result is capped at multiplierMax so the tail is bounded.
 * Cost per spin: O(1) — one division + one min.
 */
public class MultiplierDistribution {

    private final double max;

    public MultiplierDistribution(SimulationRequest req) {
        this.max = req.getMultiplierMax();
    }

    public double sample(double u) {
        return Math.min(max, 1.0 / (1.0 - u));
    }
}
