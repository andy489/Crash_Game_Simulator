package com.crash.simulation;

import com.crash.model.ReductionResult;
import com.crash.stats.MedianTracker;
import com.crash.web.SimulationRequest;
import java.util.Random;
import java.util.concurrent.Callable;

public class WorkerThread implements Callable<ReductionResult> {

    private final int threadId;
    private final int spins;
    private final double stake;
    private final int zeroSpinInterval;
    private final MultiplierDistribution distribution;

    public WorkerThread(int threadId, int spins, SimulationRequest req) {
        this.threadId = threadId;
        this.spins = spins;
        this.stake = req.getStake();
        this.zeroSpinInterval = req.getZeroSpinInterval();
        this.distribution = new MultiplierDistribution(req);
    }

    @Override
    public ReductionResult call() {
        Random rng = new Random();

        double totalStaked = 0;
        double totalReturned = 0;
        int wins = 0;
        int hitRate = 0;
        double localMax = Double.MIN_VALUE;
        double sumMultiplier = 0;
        MedianTracker medianTracker = new MedianTracker();

        for (int i = 0; i < spins; i++) {
            totalStaked += stake;
            double stakeMultiplier;

            if (zeroSpinInterval > 0 && (i + 1) % zeroSpinInterval == 0) {
                stakeMultiplier = 0.0;
            } else {
                double raw = distribution.sample(rng.nextDouble());
                stakeMultiplier = Math.round(raw * 10) / 10.0;
                hitRate++;

                if (stakeMultiplier > 1.0) {
                    wins++;
                }
            }

            if (stakeMultiplier > localMax) localMax = stakeMultiplier;
            sumMultiplier += stakeMultiplier;
            medianTracker.add(stakeMultiplier);
            totalReturned += stake * stakeMultiplier;
        }

        return new ReductionResult(threadId, spins, wins, hitRate, totalStaked, totalReturned,
                localMax, sumMultiplier / spins, medianTracker.median());
    }
}
