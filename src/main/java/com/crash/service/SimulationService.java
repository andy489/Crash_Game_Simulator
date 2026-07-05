package com.crash.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.crash.model.ReductionResult;
import com.crash.model.SimulationResult;
import com.crash.simulation.WorkerThread;
import com.crash.web.SimulationRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseEmitter run(SimulationRequest req) {
        SseEmitter emitter = new SseEmitter(0L);
        runAsync(req, emitter);
        return emitter;
    }

    @Async
    void runAsync(SimulationRequest req, SseEmitter emitter) {
        int N = req.getThreadCount();
        int totalSpins = req.getSpins();
        log.info("Simulation started — rounds={} threads={} cashOut={}x strategy={}", totalSpins, N,
                req.getCashOutMultiplier(),
                req.getStrategyWeights() != null && !req.getStrategyWeights().isBlank() ? req.getStrategyWeights() : "none");
        long start = System.nanoTime();

        try (ExecutorService executor = Executors.newFixedThreadPool(N)) {
            List<Future<ReductionResult>> futures = new ArrayList<>(N);

            int base = totalSpins / N;
            int remainder = totalSpins % N;

            for (int i = 0; i < N; i++) {
                int spins = base + (i < remainder ? 1 : 0);
                futures.add(executor.submit(new WorkerThread(i, spins, req)));
            }

            double grandStaked = 0;
            double grandReturned = 0;
            int totalWins = 0;
            double globalMax = 1.0;
            double sumAvgCrash = 0;
            double sumMedians = 0;
            double sumP90s = 0;
            double sumSquaredCrash = 0;
            long[] mergedHistogram = null;

            for (Future<ReductionResult> future : futures) {
                ReductionResult r = future.get();
                log.info("Thread {} finished — rounds={}", r.threadId(), r.spins());

                grandStaked += r.totalStaked();
                grandReturned += r.totalReturned();
                totalWins += r.wins();
                if (r.maxMultiplier() > globalMax) globalMax = r.maxMultiplier();
                sumAvgCrash += r.avgCrashMultiplier() * r.spins();
                sumMedians += r.medianCrashMultiplier();
                sumP90s += r.p90CrashMultiplier();
                sumSquaredCrash += r.sumSquaredCrash();

                long[] hist = r.histogramCounts();
                if (mergedHistogram == null) {
                    mergedHistogram = hist.clone();
                } else {
                    for (int b = 0; b < mergedHistogram.length; b++) mergedHistogram[b] += hist[b];
                }

                emitter.send(SseEmitter.event()
                    .name("thread")
                    .data(objectMapper.writeValueAsString(r)));
            }

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("Simulation complete — elapsedMs={} rtp={}%", elapsedMs,
                    String.format("%.4f", grandReturned / grandStaked * 100.0));

            double globalAvg = sumAvgCrash / totalSpins;
            double variance = (sumSquaredCrash / totalSpins) - (globalAvg * globalAvg);
            double stdDev = Math.sqrt(Math.max(0, variance));
            double globalMedian = sumMedians / N;
            double globalP90 = sumP90s / N;
            // Volatility index = p90/p50: captures tail weight independent of mean magnitude.
            double volatilityIndex = globalMedian > 0 ? globalP90 / globalMedian : 0;
            String volatilityLabel = volatilityIndex < 3.0 ? "Low"
                : volatilityIndex < 6.0 ? "Medium"
                : volatilityIndex < 12.0 ? "High"
                : "Extreme";

            // Compute effective cash-out multiplier for display
            boolean isStrategy = req.getStrategyWeights() != null && !req.getStrategyWeights().isBlank();
            double effectiveCashOut;
            String swOut = "";
            String smOut = "";
            if (isStrategy) {
                String[] wParts = req.getStrategyWeights().split(",");
                String[] mParts = req.getStrategyMultipliers().split(",");
                double wSum = 0, wmSum = 0;
                for (int i = 0; i < wParts.length; i++) {
                    double w = Double.parseDouble(wParts[i].trim());
                    double m = Double.parseDouble(mParts[i].trim().replace("x", ""));
                    wSum += w; wmSum += w * m;
                }
                effectiveCashOut = wmSum / wSum;
                swOut = req.getStrategyWeights().trim();
                smOut = req.getStrategyMultipliers().trim();
            } else {
                effectiveCashOut = req.getCashOutMultiplier();
            }

            SimulationResult result = new SimulationResult(
                totalSpins,
                totalWins * 100.0 / totalSpins,
                effectiveCashOut,
                globalMax,
                globalAvg,
                globalMedian,
                grandStaked,
                grandReturned,
                grandReturned / grandStaked * 100.0,
                stdDev,
                volatilityIndex,
                volatilityLabel,
                elapsedMs,
                swOut,
                smOut,
                mergedHistogram != null ? mergedHistogram : new long[40],
                buildHistogramEdges(req.getMultiplierMax(), mergedHistogram != null ? mergedHistogram.length : 40)
            );

            emitter.send(SseEmitter.event()
                .name("complete")
                .data(objectMapper.writeValueAsString(result)));

            emitter.complete();

        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private static double[] buildHistogramEdges(double max, int buckets) {
        double[] edges = new double[buckets + 1];
        double logMax = Math.log(max);
        for (int i = 0; i <= buckets; i++) {
            edges[i] = Math.exp(logMax * i / buckets);
        }
        edges[0] = 1.0;
        return edges;
    }
}
