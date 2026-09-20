# Upstream provenance

Benchy is derived from the benchmark framework in [XTDB](https://github.com/xtdb/xtdb), revision `42fec0883567d643ac8e39edce5cfc881a6bcdc8`.
The extraction used the local XTDB checkout on 2026-09-20.

Extracted source:

- `modules/bench/src/main/clojure/xtdb/bench.clj`: task composition, workers, random sampling, stage and transaction instrumentation, system information, and reporting.
- `modules/bench/src/main/clojure/xtdb/bench/random.clj`: seeded data generators.
- `modules/bench/src/main/clojure/xtdb/bench/stats.clj`: distribution summaries and statistical sampling.

The original Mozilla Public License 2.0 is retained in `LICENSE`.
The library JAR includes both the license and this provenance record under `META-INF`.

The runner has been adapted for caller-supplied context, independent run state and metrics, cooperative cancellation, counted transaction failures, structured results, and concurrent JSON Lines reporting.
Weighted samplers use a strict probability boundary so zero-weight entries cannot be selected by an RNG draw of zero.
The alias sampler also retains residual weights without the upstream epsilon cutoff.
Concrete workloads, datasets, XTDB node lifecycle operations, cloud automation, and CLI dispatch were not extracted.
