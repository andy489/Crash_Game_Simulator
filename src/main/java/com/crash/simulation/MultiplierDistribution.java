package com.crash.simulation;

import com.crash.web.SimulationRequest;

/**
 * Encapsulates the four-segment piecewise power-law distribution.
 * Pre-computes CDF boundaries at construction time; {@link #sample(double)}
 * converts a uniform random variate u ∈ [0,1) to a multiplier value via
 * the inverse-CDF: x = boundaryLow / (1 − u)^(1/exponent).
 */
public class MultiplierDistribution {

    private final double multiplierMin;
    private final double multiplierMid1;
    private final double multiplierMid2;
    private final double multiplierMid3;
    private final double multiplierMax;

    private final double crashExponentLow;
    private final double crashExponentMid;
    private final double crashExponentUpperMid;
    private final double crashExponentHigh;

    private final double pMid1;
    private final double pMid2;
    private final double pMid3;

    public MultiplierDistribution(SimulationRequest req) {
        this.multiplierMin      = req.getMultiplierMin();
        this.multiplierMid1     = req.getMultiplierMid1();
        this.multiplierMid2     = req.getMultiplierMid2();
        this.multiplierMid3     = req.getMultiplierMid3();
        this.multiplierMax      = req.getMultiplierMax();
        this.crashExponentLow      = req.getCrashExponentLow();
        this.crashExponentMid      = req.getCrashExponentMid();
        this.crashExponentUpperMid = req.getCrashExponentUpperMid();
        this.crashExponentHigh     = req.getCrashExponentHigh();

        this.pMid1 = 1.0 - Math.pow(multiplierMin / multiplierMid1, crashExponentLow);
        this.pMid2 = pMid1 + (1.0 - pMid1) * (1.0 - Math.pow(multiplierMid1 / multiplierMid2, crashExponentMid));
        this.pMid3 = pMid2 + (1.0 - pMid2) * (1.0 - Math.pow(multiplierMid2 / multiplierMid3, crashExponentUpperMid));
    }

    public double sample(double u) {
        if (u < pMid1) {
            double uSeg = u / pMid1;
            return Math.min(multiplierMin / Math.pow(1.0 - uSeg, 1.0 / crashExponentLow), multiplierMid1);
        } else if (u < pMid2) {
            double uSeg = (u - pMid1) / (pMid2 - pMid1);
            return Math.min(multiplierMid1 / Math.pow(1.0 - uSeg, 1.0 / crashExponentMid), multiplierMid2);
        } else if (u < pMid3) {
            double uSeg = (u - pMid2) / (pMid3 - pMid2);
            return Math.min(multiplierMid2 / Math.pow(1.0 - uSeg, 1.0 / crashExponentUpperMid), multiplierMid3);
        } else {
            double uSeg = (u - pMid3) / (1.0 - pMid3);
            return Math.min(multiplierMid3 / Math.pow(1.0 - uSeg, 1.0 / crashExponentHigh), multiplierMax);
        }
    }
}
