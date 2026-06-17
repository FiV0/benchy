# benchy

A small, **database-agnostic** benchmark-running engine, extracted from XTDB's
`xtdb.bench`. It gives you:

- a declarative **task DSL** (`:do`, `:call`, `:pool`, `:concurrently`,
  `:pick-weighted`, `:freq-job`),
- **Micrometer** instrumentation of stages and transactions,
- timing + JSON/structured **reporting**,
- an optional **Prometheus `/metrics`** endpoint for Grafana,
- a **CLI** plus `cli-flags` / `->benchmark` extension points.

The harness knows nothing about any particular database — a benchmark supplies
the system it runs against via a `:->sut` function.

## Defining a benchmark

Put your benchmark in a `benchy.<type>` namespace (or set the prefix via the
`benchy.ns-prefix` system property / `BENCHY_NS_PREFIX` env var) and implement
the multimethods:

```clojure
(ns benchy.mybench
  (:require [benchy.bench :as bench])
  (:import (java.time Duration)))

;; optional: extra CLI flags
(defmethod bench/cli-flags :mybench [_]
  [[nil "--duration DURATION" :id :duration
    :default (Duration/parse "PT5S") :parse-fn #(Duration/parse %)]])

(defmethod bench/->benchmark :mybench [_ {:keys [duration] :as _opts}]
  {:title "my benchmark"
   :benchmark-type :mybench
   :parameters {:duration duration}
   ;; ->sut returns {:node ..., :registry <optional>, :close <optional fn>}
   :->sut (fn [_opts] {:node (atom {})})
   :tasks [{:t :concurrently
            :duration duration
            :join-wait (Duration/ofSeconds 5)
            :thread-tasks
            [{:t :freq-job :duration duration :freq (Duration/ofMillis 10)
              :job-task {:t :call :transaction :insert
                         :f (bench/wrap-in-catch
                             (fn [worker] (swap! (:node worker) assoc (rand-int 1e6) :x)))}}]}]})
```

Each task fn receives a `worker` whose `:node` is whatever your `:->sut`
returned. Worker helpers (`bench/random-str`, `bench/random-nth`, `bench/rng`,
counters, …) are seeded for reproducibility.

See `src/benchy/example.clj` for a complete, runnable example (`:example`).

## Running

```sh
# the bundled example; --metrics-port defaults to 8080 (0 disables it)
clj -M:bench example --duration PT5S
# or
clj -M -m benchy.bench example --duration PT5S --metrics-port 0
```

Build an uberjar and run it:

```sh
clj -T:build clean
clj -T:build jar
java -jar target/benchy-0.1.*.jar example --duration PT5S
```

## Metrics & Grafana

Unlike the original XTDB module (where the XTDB node exposed `/metrics`), benchy
exposes its own Prometheus endpoint. With `--metrics-port 8080` (the default),
benchmark transaction timers are scrapeable at `http://localhost:8080/metrics`.

A local Prometheus + Grafana stack is provided:

```sh
clj -M:bench example --duration PT5M           # leave it running
docker compose -f monitoring/docker-compose.yaml up
# Grafana: http://localhost:3001  (Prometheus scrapes host:8080/metrics)
```

## Testing

```sh
clj -X:test
```

## License
