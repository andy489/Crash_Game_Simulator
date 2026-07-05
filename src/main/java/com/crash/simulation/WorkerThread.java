package com.crash.simulation;

import com.crash.model.ReductionResult;
import com.crash.stats.MedianTracker;
import com.crash.web.SimulationRequest;
import java.util.Random;
import java.util.concurrent.Callable;

public class WorkerThread implements Callable<ReductionResult> {

    private static final int HIST_BUCKETS = 40;

    private final int threadId;
    private final int spins;
    private final double stake;
    private final double cashOutMultiplier;
    private final int instantCrashInterval;
    private final MultiplierDistribution distribution;
    private final double logMax;

    // strategy mode fields (null when single-target)
    private final double[] strategyMultipliers;
    private final double[] cumulativeWeights;

    public WorkerThread(int threadId, int spins, SimulationRequest req) {
        this.threadId = threadId;
        this.spins = spins;
        this.stake = req.getStake();
        this.cashOutMultiplier = req.getCashOutMultiplier();
        this.instantCrashInterval = req.getInstantCrashInterval();
        this.distribution = new MultiplierDistribution(req);
        this.logMax = Math.log(req.getMultiplierMax());

        boolean isStrategy = req.getStrategyWeights() != null && !req.getStrategyWeights().isBlank();
        if (isStrategy) {
            String[] wParts = req.getStrategyWeights().split(",");
            String[] mParts = req.getStrategyMultipliers().split(",");
            int n = wParts.length;
            strategyMultipliers = new double[n];
            cumulativeWeights = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += Double.parseDouble(wParts[i].trim());
                cumulativeWeights[i] = sum;
                strategyMultipliers[i] = Double.parseDouble(mParts[i].trim().replace("x", ""));
            }
            // normalise to [0,1]
            for (int i = 0; i < n; i++) cumulativeWeights[i] /= sum;
        } else {
            strategyMultipliers = null;
            cumulativeWeights = null;
        }
    }

    @Override
    public ReductionResult call() {
        Random rng = new Random();

        double totalStaked = 0;
        double totalReturned = 0;
        int wins = 0;
        double localMax = 1.0;
        double sumCrashMultiplier = 0;
        double sumSquaredCrash = 0;
        MedianTracker medianTracker = new MedianTracker();
        long[] histogram = new long[HIST_BUCKETS];

        for (int i = 0; i < spins; i++) {
            totalStaked += stake;

            double crashPoint;
            if (instantCrashInterval > 0 && (i + 1) % instantCrashInterval == 0) {
                crashPoint = 1.0;
            } else {
                crashPoint = distribution.sample(rng.nextDouble());
            }

            if (crashPoint > localMax) {
                localMax = crashPoint;
            }

            sumCrashMultiplier += crashPoint;
            sumSquaredCrash += crashPoint * crashPoint;
            medianTracker.add(crashPoint);

            // log-uniform bucket: index = floor(log(x) / log(max) * HIST_BUCKETS)
            int bucket = (int) (Math.log(Math.max(crashPoint, 1.0)) / logMax * HIST_BUCKETS);
            if (bucket >= HIST_BUCKETS) bucket = HIST_BUCKETS - 1;
            histogram[bucket]++;

            double target = (cumulativeWeights != null)
                    ? pickWeighted(rng.nextDouble())
                    : cashOutMultiplier;

            if (crashPoint >= target) {
                wins++;
                totalReturned += stake * target;
            }
        }

        return new ReductionResult(threadId, spins, wins, totalStaked, totalReturned,
                localMax, sumCrashMultiplier / spins, medianTracker.median(),
                medianTracker.percentile(0.90), sumSquaredCrash, histogram);
    }

    private double pickWeighted(double u) {
        int lo = 0, hi = cumulativeWeights.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulativeWeights[mid] < u) lo = mid + 1; else hi = mid;
        }
        return strategyMultipliers[lo];
    }
}
