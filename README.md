# Benchy

A composable Clojure benchmark library extracted from XTDB's benchmark framework.
Requires Clojure 1.12 and Java 21 or newer.
It supplies task orchestration, seeded random generators, transaction timing, and structured reports.
You supply the operations and any resources they need.

## Use

Add this checkout to your application's `deps.edn`:

```clojure
{:deps {io.github.FiV0/benchy {:local/root "/path/to/benchy"}}}
```

```clojure
(require '[benchy.core :as bench])
(import '(java.time Duration))

(def benchmark
  {:title "Counter example"
   :seed 42
   :->state (fn [] {:operations (atom 0)})
   :tasks [{:t :call
            :stage :setup
            :setup? true
            :f (fn [{:keys [context]}]
                 (reset! (:counter context) 0))}
           {:t :pool
            :stage :work
            :thread-count 2
            :duration (Duration/ofMillis 100)
            :think (Duration/ofMillis 1)
            :pooled-task
            {:t :call
             :transaction :increment
             :labels {"target" "counter"}
             :f (fn [{:keys [context state]}]
                  (swap! (:counter context) inc)
                  (swap! (:operations state) inc))}}]})

(def result (bench/run-benchmark benchmark {:counter (atom 0)}))
(select-keys result [:status :transaction-count :error-count :throughput])
```

`(bench/run-benchmark benchmark)` uses an empty context map.
`(bench/compile-benchmark benchmark)` validates the definition and returns a function supporting the same optional context argument.
The compiled function can be reused, including concurrently; each invocation creates independent state, metrics, RNGs, and executors.

The **context** is a caller-owned map of resources such as connections, clients, or counters.
Benchy passes it through unchanged and never closes its contents.
Use `with-open` around the benchmark call when the caller needs to manage connection lifetimes.

### SQLite example

Run the [next.jdbc](https://cljdoc.org/d/com.github.seancorfield/next.jdbc/1.3.1070/doc/getting-started)
[SQLite example](examples/benchy/examples/sqlite.clj) from this checkout:

```sh
clj -X:sqlite-example
# Optionally change the workload duration (default: 2 seconds):
clj -X:sqlite-example :duration-ms 5000
```

It seeds 1,000 counters in a fresh in-memory database, then uses one worker to
choose between primary-key lookups (80% weight) and autocommitted increments
(20% weight). It demonstrates caller-owned resources via `:context`, a `:setup?`
stage, a seeded RNG, `:pool`, `:pick-weighted`, and per-operation `:transaction`
timing. Setup time is excluded from throughput. Here, a Benchy transaction is
one measured callback; the seed inserts share a separate JDBC transaction.

The example prints a summary and a table of operation counts, errors, and latency
in milliseconds, and returns the full result map. It owns one connection and
closes it after the run, discarding the database without creating files.
The JDBC dependencies are confined to the example alias. This short run has no
warmup and includes JDBC statement preparation and result handling in its timings.

### Worker callbacks

Each callback receives a worker map containing:

| Key | Value |
| --- | --- |
| `:context` | The map passed to the run function; shared by that run's workers. |
| `:state` | A map created once per run by `:->state`; defaults to `{}` and is shared by workers. |
| `:random` | A `java.util.Random` belonging to this worker. |
| `:clock` | A UTC `java.time.Clock` for application timestamps. |
| `:bench-id` | The run's UUID. |
| `:thread-name` | The current benchmark thread's name. |

`:->state` accepts a zero-argument function or `[function & arguments]` and must return a map.
Use atoms or other thread-safe values for shared mutable state.
The `benchy/*` worker keys are internal.
Benchy owns and closes only its executors, metrics registry, and report writer.

## Tasks

Every benchmark has a `:tasks` sequence, executed in order.
Callbacks' return values are ignored by the runner.
`nil` tasks are allowed as conditional no-ops.

| `:t` | Fields and behavior |
| --- | --- |
| `:call` | `:f` is a function of worker, or `[function & arguments]`; the worker is passed first. |
| `:do` | Runs `:tasks` sequentially. |
| `:pool` | Repeats `:pooled-task` on `:thread-count` workers for `:duration`, with an optional `:think` delay after each call. |
| `:concurrently` | Runs each entry in `:thread-tasks` once on its own worker; returns when all complete. `:duration` sets the time budget. |
| `:pick-weighted` | Chooses one task from `:choices`, a sequence of `[weight task]` pairs. Empty choices do nothing. |
| `:freq-job` | Repeats `:job-task` for `:duration`, with an optional `:freq` delay after each call. |

Durations are non-negative `java.time.Duration` values, not strings.
`:pool`, `:concurrently`, and `:freq-job` require `:duration`.
Zero-duration pools and frequency jobs make no calls.
Omitted `:think` and `:freq` default to zero; they are delays after completion, not fixed-rate schedules.
Delays are capped by the remaining duration, and a running callback can finish after its repetition window ends.
Pools require a positive integer thread count.
Weighted choices require finite, non-negative weights with a finite positive total; zero-weight choices are never selected.

Pools and concurrent groups allow a `:join-wait` grace period after their duration, defaulting to five seconds.
An overrun raises `:benchy/type :task-timeout`, interrupts unfinished workers, and waits up to another `:join-wait` for cleanup.
Failure in a child task cancels its siblings immediately.

Any task can carry `:stage` for stage reporting and `:transaction` for operation timing.
Both names accept keywords or strings.
Transaction `:labels` map strings to strings.
`:setup? true` marks a task and its descendants as setup; overlapping setup intervals are counted only once.
Prefer transaction markers on leaf operations: nested transaction markers measure and count each marked invocation separately.

## Results and failures

Successful runs return a map with `:status :completed` or `:completed-with-errors`.
The result includes benchmark metadata, OSHI system information, a run UUID, and:

- `:total-time-ms`, `:setup-time-ms`, and `:time-taken-ms` (total minus setup), measured with a monotonic clock.
- `:transactions`, a vector grouped by transaction name, labels, and setup status.
- Per-group `:count`, `:success-count`, `:error-count`, `:total-time-ms`, `:mean-ms`, `:max-ms`, and approximate Micrometer percentiles from `:p50-ms` through `:p99.9-ms`.
- `:transaction-count`, including successful and failed measured calls and setup calls.
- `:throughput`, successful non-setup calls per measured elapsed second, including framework overhead and idle time.
- `:error-count` and up to ten error samples, each with its class, message (up to 1,024 characters), transaction, and labels.

Timing summaries include both successful and failed calls.
Registry creation and system information collection happen before the measured run begins.
State initialization and executor startup/cleanup are included in the run's elapsed time.
Micrometer records per-run cumulative counts and totals, with a 365-day distribution expiry interval.

Ordinary exceptions in measured transactions are counted and execution continues.
Setup failures, exceptions from uninstrumented tasks, cancellation, and JVM errors stop the run.
For ordinary fatal failures, an `ExceptionInfo` carries `:partial-result`, with the original exception as its cause and its exception data preserved.
The partial result has `:status :failed`, `:timed-out`, or `:cancelled` and a `:failure` description.
JVM errors and `InterruptedException` retain their identity; their partial result is attached as a suppressed `ExceptionInfo`.
Caller interruption is preserved.

Benchmark-level `:timeout` bounds execution, including state initialization; omit it or use `Duration/ZERO` for no timeout.
Cancellation waits up to the benchmark-level `:join-wait` (five seconds by default) for the runner to stop.
Worker threads are daemon threads, and cancellation is cooperative: callbacks that ignore interruption can outlive the call.
Such callbacks may still access caller-owned resources; late framework metrics and report writes are discarded after finalization.
Benchy never exits the JVM, shuts down Clojure's global agent pools, or installs shutdown hooks.

## Reports and helpers

Set `:bench-log-file` on the benchmark to append JSON Lines events for initialization, stage starts/ends, and the final summary.
Concurrent runs within one JVM can share a file; use `:bench-id` to group their records.
Use separate files for separate JVM processes.
`(bench/log-report worker event-map)` appends a custom event.
The library does not print reports to stdout.
Metadata in `:parameters` and custom events must be JSON-compatible; durations, instants, and UUIDs are encoded as strings without changing Clojure's global JSON serialization.
There is no bundled logging provider; applications may supply an SLF4J provider for OSHI/Micrometer diagnostics.

`benchy.random` provides seeded integers, longs, strings, UUIDs, dates/times, selection, and Fisher–Yates shuffle.
`benchy.core` retains worker-oriented helpers such as `rng`, `random-nth`, `sample-flat`, `sample-gaussian`, and `weighted-sample-fn`.
`random-str` retains upstream's hexadecimal byte-based sizing; use `benchy.random/next-string` for an exact character count.
`random-seq` draws a length in `[min, max)` and applies `distinct` afterward when `:unique` is true, so it can return fewer distinct values.
`set-count!` stores one greater than its argument, `inc-count!` returns the old counter value, and counter sampling uses `[0, count)` (or `nil` when empty).
Worker RNG seeds are derived in task order and dynamic Clojure bindings are conveyed to worker threads.
The same seed reproduces each worker's random sequence, but scheduling, elapsed-time workloads, shared mutable state, and calendar-relative generators can change results.

`benchy.stats` provides distribution summaries, nanosecond timing summaries, and log-normal/Poisson draws.
Its exact sample percentiles use index `floor(p * (n - 1))`, unlike the approximate Micrometer histograms used by the runner.
Empty summary inputs return `nil`.

## Development

```sh
clj -X:test
clj -T:build jar
```

The build creates a regular library JAR and Maven metadata under `target/`; dependencies are not bundled.
The optional SQLite example runs from the checkout and is not included in the JAR.
See [UPSTREAM.md](UPSTREAM.md) for the extraction revision and [LICENSE](LICENSE) for the Mozilla Public License 2.0.
