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
        log.info("Simulation started — spins={} threads={}", totalSpins, N);
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
            int totalHitRate = 0;
            double globalMax = Double.MIN_VALUE;
            double sumStakeMultipliers = 0;
            double sumMedians = 0;

            for (Future<ReductionResult> future : futures) {
                ReductionResult r = future.get();
                log.info("Thread {} finished — spins={}", r.threadId(), r.spins());

                grandStaked += r.totalStaked();
                grandReturned += r.totalReturned();
                totalWins += r.wins();
                totalHitRate += r.hitRate();
                if (r.maxMultiplier() > globalMax) globalMax = r.maxMultiplier();
                sumStakeMultipliers += r.avgMultiplier() * r.spins();
                sumMedians += r.medianMultiplier();

                emitter.send(SseEmitter.event()
                    .name("thread")
                    .data(objectMapper.writeValueAsString(r)));
            }

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.info("Simulation complete — elapsedMs={} rtp={}%", elapsedMs,
                    String.format("%.4f", grandReturned / grandStaked * 100.0));

            SimulationResult result = new SimulationResult(
                totalSpins,
                totalHitRate * 100.0 / totalSpins,
                totalWins * 100.0 / totalSpins,
                globalMax,
                sumStakeMultipliers / totalSpins,
                sumMedians / N,
                grandStaked,
                grandReturned,
                grandReturned / grandStaked * 100.0,
                elapsedMs
            );

            emitter.send(SseEmitter.event()
                .name("complete")
                .data(objectMapper.writeValueAsString(result)));

            emitter.complete();

        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
