# Crash Game Simulator

A high-performance, multithreaded **crash-game distribution simulator** built with Spring Boot 3 and vanilla JS. Runs up to 1 billion spins across up to 8 parallel worker threads, streams per-thread results live via Server-Sent Events, and renders global statistics in a responsive web UI.

![Crash Simulator Demo](demo.gif)

---

## Table of Contents

1. [What It Does](#what-it-does)
2. [Architecture Overview](#architecture-overview)
3. [The Math: Four-Segment Power-Law Distribution](#the-math-four-segment-power-law-distribution)
4. [Concurrent Reduction Pattern](#concurrent-reduction-pattern)
5. [Median Estimation: Knuth Reservoir Sampling](#median-estimation-knuth-reservoir-sampling)
6. [Package Structure](#package-structure)
7. [Class-by-Class Breakdown](#class-by-class-breakdown)
8. [Configuration Reference](#configuration-reference)
9. [HTTP API](#http-api)
10. [SSE Streaming Protocol](#sse-streaming-protocol)
11. [Frontend Architecture](#frontend-architecture)
12. [Internationalization (i18n)](#internationalization-i18n)
13. [Theming System](#theming-system)
14. [Running Locally](#running-locally)
15. [Performance Characteristics](#performance-characteristics)

---

## What It Does

The simulator models a **crash-style gambling game** where each round ("spin") produces a random multiplier drawn from a configurable piecewise power-law distribution. The key simulation questions it answers are:

- What is the long-run **Return-to-Player (RTP)** for a given distribution shape?
- What are the **hit rate** (fraction of non-zero spins) and **win rate** (fraction of spins returning > 1×)?
- What is the **median multiplier** across hundreds of millions of spins?
- How fast does each **thread segment** complete, and how evenly is work distributed?

A single run of 500 million spins at 8 threads completes in approximately **2–4 seconds** on a modern multi-core machine.

---

## Architecture Overview

```
Browser
  │
  │  GET /                       → Thymeleaf form (config pre-filled from application.yml)
  │  GET /simulate/validate?...  → JSON validation result
  │  GET /simulate/stream?...    → SSE stream (text/event-stream)
  │
  ▼
SimulationController
  │
  ├── validate()   → SimulationValidator.validate(req) → 200 OK or 400 + errors[]
  │
  └── stream()     → SimulationService.run(req)
                         │
                         │  @Async — runs on Spring's task executor
                         │
                         ├── ExecutorService (N threads, virtual-thread-friendly)
                         │       ├── WorkerThread(0, spins/N, req) ──► ReductionResult
                         │       ├── WorkerThread(1, spins/N, req) ──► ReductionResult
                         │       ├── ...
                         │       └── WorkerThread(N-1, spins/N±1, req) ──► ReductionResult
                         │
                         ├── future.get() × N  →  SSE event "thread" per completion
                         │
                         └── aggregate()       →  SSE event "complete" → emitter.complete()
```

The SSE transport means the browser receives thread results **as they finish**, not in a batch at the end. With 8 threads each doing ~62.5 million spins, results typically stream in within a 200–500 ms window.

---

## The Math: Four-Segment Power-Law Distribution

The multiplier for each spin is drawn from a **piecewise inverse-CDF** with four segments, each following an independent power-law (Pareto-type) shape.

### Boundaries and Segments

```
Segment 1: [multiplierMin,  multiplierMid1)   exponent: crashExponentLow
Segment 2: [multiplierMid1, multiplierMid2)   exponent: crashExponentMid
Segment 3: [multiplierMid2, multiplierMid3)   exponent: crashExponentUpperMid
Segment 4: [multiplierMid3, multiplierMax]    exponent: crashExponentHigh
```

### CDF and Inverse-CDF per Segment

Within each segment the CDF is a rescaled Pareto:

```
P(X ≤ x | X in segment starting at a) = 1 − (a / x)^α
```

where `a` is the lower boundary of the segment and `α` is the crash exponent for that segment.

The **inverse-CDF** (used for sampling) is:

```
x = a / (1 − u)^(1/α)
```

where `u ~ Uniform[0, 1)` is rescaled to the segment's probability range.

### Segment Probability Boundaries (Pre-computed at construction)

```java
pMid1 = 1 − (multiplierMin / multiplierMid1)^crashExponentLow
pMid2 = pMid1 + (1 − pMid1) × (1 − (multiplierMid1 / multiplierMid2)^crashExponentMid)
pMid3 = pMid2 + (1 − pMid2) × (1 − (multiplierMid2 / multiplierMid3)^crashExponentUpperMid)
```

A uniform variate `u` is compared to `pMid1`, `pMid2`, `pMid3` to select the segment, then rescaled to `[0, 1)` within that segment before applying the inverse-CDF formula.

### Default Configuration Shape

With the shipped defaults:

| Boundary | Value | Exponent | Meaning |
|----------|-------|----------|---------|
| Min | 0.1 | Low = 1.0 | Pure Pareto from 0.1× to 100× |
| Mid 1 | 100.0 | Mid = 1.9 | Steeper from 100× to 300× |
| Mid 2 | 300.0 | UpperMid = 4.0 | Very steep from 300× to 1000× |
| Mid 3 | 1000.0 | High = 9.0 | Extremely rare tail up to 3000× |

The `zeroSpinInterval = 36` setting forces every 36th spin to return `0×`, which reduces the effective RTP by ~2.7% and is excluded from the hit-rate count.

---

## Concurrent Reduction Pattern

The simulation uses a classic **fork-join parallel reduction**:

1. **Fork** — `N` independent `WorkerThread` tasks are submitted to a fixed-thread-pool `ExecutorService`. Each thread gets `⌊totalSpins / N⌋` spins; the first `totalSpins % N` threads get one extra spin, ensuring exact total with no spin left unaccounted.

2. **Compute** — each thread runs its entire spin loop locally, accumulating: `totalStaked`, `totalReturned`, `wins`, `hitRate`, `localMax`, `sumMultiplier`, and a `MedianTracker` reservoir.

3. **Join** — the orchestrator calls `future.get()` sequentially. As each `ReductionResult` arrives it is merged into the global accumulators using type-appropriate reduction operations:
   - **Summation**: `grandStaked`, `grandReturned`, `totalWins`, `totalHitRate`
   - **Max**: `globalMax = max(globalMax, r.maxMultiplier())`
   - **Weighted average**: `sumStakeMultipliers += r.avgMultiplier() * r.spins()`
   - **Arithmetic mean of per-thread medians**: `sumMedians += r.medianMultiplier()` (then divide by N)

4. Each `ReductionResult` is immediately serialized to JSON and pushed as an SSE `"thread"` event, providing live streaming even before all threads finish.

5. After all futures are resolved, a `SimulationResult` carrying the global aggregates is pushed as a `"complete"` SSE event and the emitter is closed.

---

## Median Estimation: Knuth Reservoir Sampling

Computing an exact median from 500 million doubles would require either sorting all values (O(n log n), ~4 GB of memory per thread) or a streaming quantile algorithm. Instead, `MedianTracker` implements **Algorithm R** from Knuth's *The Art of Computer Programming*, Vol. 2.

### Algorithm

```
count = 0
reservoir[0..k-1]   (k = 100,000)

for each value v:
    count++
    if count ≤ k:
        reservoir[count - 1] = v          // fill phase
    else:
        j = random integer in [0, count)
        if j < k:
            reservoir[j] = v              // replace phase
```

### Why It Works

At any point after the fill phase, each of the `count` values seen so far has **exactly `k / count`** probability of occupying a slot in the reservoir. This is the uniform-sampling invariant: the reservoir is always a simple random sample without replacement from all values seen so far.

**Proof sketch** (by induction): After element `n+1` arrives, the replacement probability is `k/(n+1)`. Each existing reservoir element survives with probability `1 − (1/(n+1)) = n/(n+1)`. So its total probability of being in the reservoir after `n+1` steps is `(k/n) × (n/(n+1)) = k/(n+1)`. ∎

### Resource Usage

| Property | Value |
|----------|-------|
| Memory per thread | `100,000 × 8 bytes = 800 KB` |
| Time per spin | O(1) amortized — 1 RNG call, 1 conditional write |
| Median error at 500M spins | < 0.1% |
| Compared to exact | 4 GB → 800 KB reduction (×5000) |

---

## Package Structure

```
src/main/java/com/threadmax/
├── SlotApplication.java              Entry point — @SpringBootApplication + @EnableAsync
│
├── model/
│   ├── SimulationConfig.java         @ConfigurationProperties("slot") — default values from application.yml
│   ├── SimulationResult.java         Record — global aggregated result sent in SSE "complete" event
│   └── ReductionResult.java          Record — per-thread result sent in SSE "thread" event
│
├── web/
│   ├── SimulationController.java     HTTP layer: GET /, GET /simulate/validate, GET /simulate/stream
│   ├── SimulationRequest.java        DTO — all 13 parameters bound from query string
│   └── SimulationValidator.java      Pure-static server-side validation with detailed error messages
│
├── service/
│   └── SimulationService.java        Orchestrator — @Async executor, fork-join, SSE emission
│
├── simulation/
│   ├── WorkerThread.java             Callable<ReductionResult> — the hot spin loop
│   └── MultiplierDistribution.java   Four-segment inverse-CDF sampler, immutable, thread-safe
│
└── stats/
    └── MedianTracker.java            Knuth Algorithm R reservoir sampler, O(k) memory
```

---

## Class-by-Class Breakdown

### `SlotApplication`
The Spring Boot entry point. `@EnableAsync` activates Spring's async task executor, which is required for `SimulationService.runAsync()` to run on a background thread rather than the HTTP request thread.

### `SimulationConfig`
A Lombok `@Data` class annotated with `@ConfigurationProperties(prefix = "slot")`. Spring Boot auto-binds all 13 simulation parameters from the `slot:` block in `application.yml` at startup. This bean is injected into `SimulationController` to pre-populate the HTML form with sensible defaults.

### `SimulationRequest`
A Lombok `@Data` DTO mirroring `SimulationConfig`'s 13 fields. Spring MVC binds query parameters to it via `@ModelAttribute` in the controller. Kept separate from `SimulationConfig` to avoid accidentally mutating application-level defaults.

### `SimulationValidator`
A stateless utility class with a single public method `validate(SimulationRequest)` returning a `List<String>` of error messages. Validates:
- Spins: 1–1,000,000,000
- Thread count: 1–8
- Stake: 0.1–200.0, divisible by 0.1
- Zero spin interval: ≥ 0
- All five multiplier boundaries: must be divisible by 0.1 and strictly ordered Min < Mid1 < Mid2 < Mid3 < Max
- All four crash exponents: must be > 0

The `isDivisibleBy0_1` helper uses integer rounding with a tolerance of 1e-9 to avoid floating-point representation issues.

### `SimulationController`
Three endpoints:

| Method | Path | Role |
|--------|------|------|
| `GET` | `/` | Renders Thymeleaf template with `SimulationConfig` model attribute |
| `GET` | `/simulate/validate` | Returns `{"valid":true}` or `{"valid":false,"errors":[...]}` |
| `GET` | `/simulate/stream` | Returns `SseEmitter` — opens SSE stream, fires async simulation |

The validation and stream endpoints are separated to avoid a Spring MVC conflict: `SseEmitter` does not compose with `ResponseEntity`, so mixing validation errors and streaming in a single endpoint causes `getOutputStream() already called` exceptions.

### `SimulationService`
The core orchestrator. `run()` creates a zero-timeout `SseEmitter` (no timeout) and delegates to `runAsync()` which is annotated `@Async` so it returns immediately to the HTTP thread.

Inside `runAsync()`:
1. Creates a `Executors.newFixedThreadPool(N)` via try-with-resources (auto-shutdown on completion).
2. Distributes spins: each thread gets `spins/N`; the first `spins%N` threads get one extra.
3. Submits `WorkerThread` callables and collects `Future<ReductionResult>`.
4. For each future: calls `future.get()` (blocking), logs completion, accumulates into global stats, and immediately emits a `"thread"` SSE event.
5. After all futures: computes final `SimulationResult`, emits a `"complete"` SSE event, calls `emitter.complete()`.

### `WorkerThread`
The hot path. Implements `Callable<ReductionResult>`. Key decisions:
- Uses a **local `Random`** (not `ThreadLocalRandom`) — avoids contention and provides independent per-thread sequences.
- The `zeroSpinInterval` check: `(i + 1) % zeroSpinInterval == 0` (1-based). Zero spins still contribute to `totalStaked` and `localMax` but not to `hitRate` or `wins`.
- Multiplier rounding: `Math.round(raw * 10) / 10.0` — matches the 0.1-granularity visible in the UI.
- Win condition: `stakeMultiplier > 1.0` (returns more than wagered).

### `MultiplierDistribution`
Pre-computes the three CDF boundary values (`pMid1`, `pMid2`, `pMid3`) once at construction. `sample(double u)` is a pure function — no state mutation — so the same instance is safely used across all spins within a thread. The four `if/else if` branches select the segment and apply the inverse-CDF formula with a `Math.min` cap at the segment's upper boundary to prevent overflow at the boundary edge.

### `MedianTracker`
Described in detail in the [Median Estimation](#median-estimation-knuth-reservoir-sampling) section. The `median()` method copies the live portion of the reservoir (`min(count, CAPACITY)` elements) before sorting to avoid mutating the in-use array.

---

## Configuration Reference

All parameters live in `src/main/resources/application.yml` under the `slot:` prefix and can be overridden per-request via form inputs.

| Parameter | Default | Range | Description |
|-----------|---------|-------|-------------|
| `spins` | 500,000,000 | 1–1,000,000,000 | Total spin count across all threads |
| `threadCount` | 4 | 1–8 | Number of parallel worker threads |
| `stake` | 1.0 | 0.1–200.0 | Fixed wager per spin |
| `multiplierMin` | 0.1 | > 0 | Distribution floor |
| `multiplierMid1` | 100.0 | < Mid2 | Segment 1/2 boundary |
| `multiplierMid2` | 300.0 | < Mid3 | Segment 2/3 boundary |
| `multiplierMid3` | 1000.0 | < Max | Segment 3/4 boundary |
| `multiplierMax` | 3000.0 | > Mid3 | Distribution ceiling |
| `crashExponentLow` | 1.0 | > 0 | Segment 1 steepness |
| `crashExponentMid` | 1.9 | > 0 | Segment 2 steepness |
| `crashExponentUpperMid` | 4.0 | > 0 | Segment 3 steepness |
| `crashExponentHigh` | 9.0 | > 0 | Segment 4 (tail) steepness |
| `zeroSpinInterval` | 36 | ≥ 0 | Force 0× every Nth spin; 0 = disabled |

---

## HTTP API

### `GET /`
Returns the Thymeleaf-rendered HTML page. Passes `SimulationConfig` as model attribute `config` to pre-fill all form inputs.

### `GET /simulate/validate`

**Query params**: all 13 simulation parameters (same names as `SimulationRequest` fields)

**200 OK** (valid):
```json
{ "valid": true }
```

**400 Bad Request** (invalid):
```json
{
  "valid": false,
  "errors": [
    "Spins must be between 1 and 1,000,000,000.",
    "Stake must be divisible by 0.1."
  ]
}
```

### `GET /simulate/stream`

**Query params**: all 13 simulation parameters

**Response**: `Content-Type: text/event-stream`

Returns an SSE stream. See [SSE Streaming Protocol](#sse-streaming-protocol) below.

---

## SSE Streaming Protocol

Two event types are emitted:

### `thread` event (emitted N times — once per worker thread as it completes)

```
event: thread
data: {"threadId":0,"spins":62500000,"wins":19847231,"hitRate":60763888,
       "totalStaked":62500000.0,"totalReturned":59183641.23,
       "maxMultiplier":2847.3,"avgMultiplier":0.9469,"medianMultiplier":0.5}
```

| Field | Type | Description |
|-------|------|-------------|
| `threadId` | int | Zero-based thread index |
| `spins` | int | Spins this thread processed |
| `wins` | int | Count of spins where multiplier > 1.0 |
| `hitRate` | int | Count of non-zero spins |
| `totalStaked` | double | Sum of all stakes |
| `totalReturned` | double | Sum of all payouts |
| `maxMultiplier` | double | Highest multiplier seen |
| `avgMultiplier` | double | Mean multiplier (including zeros) |
| `medianMultiplier` | double | Reservoir-estimated median |

### `complete` event (emitted once after all threads finish)

```
event: complete
data: {"totalSpins":500000000,"hitRatePct":97.22,"winRatePct":31.75,
       "maxMultiplier":2980.1,"avgMultiplier":0.9471,"medianMultiplier":0.5,
       "totalStaked":500000000.0,"totalReturned":473551234.5,
       "rtp":94.7102,"elapsedMs":2814}
```

| Field | Type | Description |
|-------|------|-------------|
| `totalSpins` | int | Total spins simulated |
| `hitRatePct` | double | % of spins with non-zero outcome |
| `winRatePct` | double | % of spins returning > 1× |
| `maxMultiplier` | double | Global maximum across all threads |
| `avgMultiplier` | double | Weighted mean across all threads |
| `medianMultiplier` | double | Mean of per-thread reservoir medians |
| `totalStaked` | double | Grand total wagered |
| `totalReturned` | double | Grand total paid out |
| `rtp` | double | `(totalReturned / totalStaked) × 100` |
| `elapsedMs` | long | Wall-clock time from first thread submission to last result |

---

## Frontend Architecture

The entire frontend lives in a single Thymeleaf template (`src/main/resources/templates/index.html`) with no build step, no npm, and no framework.

### Form Binding
Thymeleaf `th:value="${config.fieldName}"` pre-fills all 13 inputs from `SimulationConfig`. On submit, the JS collects form values via `FormData`, builds a query string, and calls `/simulate/validate` before opening the SSE stream.

### Two-Phase Submission
```
User clicks Run
  └─► clientValidate()               (instant, no network)
        └─► fetch /simulate/validate  (server-side double-check)
              └─► new EventSource('/simulate/stream?' + params)
```

Client-side validation mirrors server-side validation for immediate feedback without a round-trip. Server-side validation exists as a security backstop.

### SSE Consumer
```js
activeSource = new EventSource('/simulate/stream?' + params);

activeSource.addEventListener('thread', ev => {
    const r = JSON.parse(ev.data);
    // append row to #threadTableBody
});

activeSource.addEventListener('complete', ev => {
    const g = JSON.parse(ev.data);
    // populate #section-results stat boxes
    activeSource.close();
});
```

### Spins Formatting
The spins field uses a live formatter that converts `500000000` to `500,000,000` as the user types, then strips commas before building the query string. Cursor position is preserved through comma insertions.

---

## Internationalization (i18n)

The UI supports four languages, all client-side with no backend involvement.

| Code | Language |
|------|----------|
| EN | English |
| BG | Български (Bulgarian) |
| RU | Русский (Russian) |
| ZH | 中文 (Chinese Simplified) |

### Mechanism
- Every translatable DOM node carries a `data-i18n="key"` attribute (text content) or `data-i18n-html="key"` (inner HTML, used for rich content like the algorithm explainer).
- A `TRANSLATIONS` object maps language codes to full key→string maps.
- `applyLang(lang)` iterates all `[data-i18n]` elements and sets their `textContent`; similarly for `[data-i18n-html]`.
- The selected language is persisted to `localStorage('lang')` and restored on page load.

### Dropdown
A custom CSS dropdown (no library) next to the theme toggle. Click opens a menu listing all four languages with the active one marked with a checkmark SVG. Closes on outside click or `Escape`.

---

## Theming System

Light/dark mode is implemented purely via CSS custom properties and a `data-theme` attribute on `<html>`.

### Token Architecture
All colors are CSS variables defined in `:root` (dark defaults) and overridden in `[data-theme="light"]`. No JavaScript touches individual element colors — switching themes is a single attribute assignment:

```js
document.documentElement.setAttribute('data-theme', 'light'); // or 'dark'
```

### Tokens (selected)
| Token | Dark | Light | Used for |
|-------|------|-------|---------|
| `--bg` | `#0f1117` | `#f0f2f7` | Page background |
| `--card-bg` | `#1e2433` | `#ffffff` | Cards and modal |
| `--accent` | `#6366f1` | `#5356d4` | Buttons, focus rings, highlights |
| `--text` | `#e2e8f0` | `#1e2433` | Body text |
| `--text-sub` | `#94a3b8` | `#5a6680` | Labels, secondary text |

### Persistence
Theme preference is stored in `localStorage('theme')`. On load, saved preference takes priority; if none, `prefers-color-scheme: light` media query is checked; otherwise dark is the default.

---

## Running Locally

### Prerequisites
- Java 21+
- Maven 3.9+

### Start

```bash
git clone <repo>
cd ConcurrentMinMaxReduction
mvn spring-boot:run
```

Open [http://localhost:8080](http://localhost:8080).

### Configuration override

Edit `src/main/resources/application.yml` to change the default form values, or simply adjust the inputs in the browser before running.

### Build fat JAR

```bash
mvn package -DskipTests
java -jar target/crash-game-simulator-1.0-SNAPSHOT.jar
```

---

## Performance Characteristics

Benchmarked on Apple M-series (8 performance cores):

| Spins | Threads | Time |
|-------|---------|------|
| 10,000,000 | 4 | ~100 ms |
| 100,000,000 | 4 | ~500 ms |
| 500,000,000 | 4 | ~1.8 s |
| 500,000,000 | 8 | ~1.0–1.5 s |
| 1,000,000,000 | 8 | ~2–3 s |

### Why It's Fast
- **No shared mutable state** during the spin loop — each `WorkerThread` accumulates into purely local variables. Zero synchronization overhead.
- **Reservoir sampler**: replaced a prior two-heap median implementation that incurred 125 million `Double` boxing allocations per thread (severe GC pressure). The `double[]` reservoir is entirely primitive.
- **Pre-computed CDF boundaries**: `MultiplierDistribution` computes `pMid1/2/3` once at construction. The hot `sample()` path is four comparisons and one `Math.pow` call.
- **Fixed-size thread pool**: avoids thread-creation overhead; pool is torn down via try-with-resources immediately after all futures complete.
