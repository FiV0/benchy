(ns benchy.bench
  "A standalone, database-agnostic benchmark-running engine.

   Extracted from XTDB's `xtdb.bench`. Provides:
     - a declarative task DSL (`compile-task` / `compile-benchmark`),
     - Micrometer-based instrumentation of stages and transactions,
     - timing + JSON/structured reporting,
     - a CLI (`-main`) and the `cli-flags` / `->benchmark` extension points.

   A benchmark supplies the system it runs against via a `:->sut` function (see
   `run-benchmark`), so the harness has no knowledge of any particular database."
  (:gen-class)
  (:require [benchy.metrics :as metrics]
            [benchy.util :as util]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log])
  (:import (io.micrometer.core.instrument MeterRegistry Timer)
           (io.micrometer.core.instrument.simple SimpleMeterRegistry)
           (java.io Writer)
           (java.lang.management ManagementFactory)
           (java.time Clock Duration)
           (java.util ArrayDeque Random)
           (java.util.concurrent Executors TimeUnit)
           (java.util.concurrent.atomic AtomicLong)
           (oshi SystemInfo)))

;; Track whether we're in shutdown mode to suppress error logging during cleanup
(defonce ^:private shutting-down? (atom false))

(extend-protocol json/JSONWriter
  Duration
  (-write [^Duration duration out _options]
    (let [^Appendable out out]
      (.append out \")
      (.append out (.toString duration))
      (.append out \"))))

(def ^:private timeout-sentinel ::benchmark-timeout)

(defn- normalize-timeout ^Duration [^Duration duration]
  (when (and duration (pos? (.toMillis duration)))
    duration))

(defn- run-with-timeout [f ^Duration timeout]
  (let [timeout (normalize-timeout timeout)
        timeout-ms (some-> timeout .toMillis)]
    (if (and timeout-ms (pos? timeout-ms))
      (let [fut (future (f))
            result (deref fut timeout-ms timeout-sentinel)]
        (if (= timeout-sentinel result)
          (do
            (future-cancel fut)
            (log/warn "Benchmark exceeded timeout, cancelling" {:timeout timeout})
            (throw (ex-info (format "Benchmark exceeded timeout (%s)" timeout)
                            {:benchy/timeout timeout})))
          result))
      (f))))

(defn wrap-in-catch [f]
  (fn [& args]
    (try
      (apply f args)
      (catch OutOfMemoryError oom
        (log/error oom "OutOfMemoryError - forcing JVM exit")
        (System/exit 1))
      (catch Throwable t
        (log/error t (str "Error while executing " f))
        (throw t)))))

(defrecord Worker [node random clock bench-id jvm-id bench-log-wrt])

(defn current-timestamp ^java.time.Instant [worker]
  (.instant ^Clock (:clock worker)))

(defn rng ^java.util.Random [worker] (:random worker))

(defn current-timestamp-ms ^long [worker] (.millis ^Clock (:clock worker)))

(defn sample-gaussian ^long [worker ^AtomicLong !counter]
  (let [v (.get !counter)]
    (-> (long (.nextGaussian (rng worker) (* v 0.5) (/ v 6.0)))
        (max 0)
        (min (dec v)))))

(defn sample-flat ^long [worker, ^AtomicLong !counter]
  (let [v (.get !counter)]
    (when (pos? v)
      (.nextLong (rng worker) v))))

(defn inc-count! [^AtomicLong !counter]
  (.getAndIncrement !counter))

(defn set-count! [^AtomicLong !counter, ^long v]
  (.set !counter (inc v)))

(defn weighted-sample-fn
  "Given a seq of [item weight] pairs, return a function which, given a
   java.util.Random, returns an item according to the weight distribution.

   Implemented with Vose's alias method
   (https://www.keithschwarz.com/darts-dice-coins/)."
  [weighted-items]
  (case (count weighted-items)
    0 (constantly nil)
    1 (constantly (ffirst weighted-items))

    (let [items (object-array (map first weighted-items))
          n (alength items)
          total (double (reduce + (map second weighted-items)))
          ;; probabilities scaled by n; values < 1 go in `small`, >= 1 in `large`
          scaled (double-array (map (fn [[_ w]] (* n (/ (double w) total))) weighted-items))
          prob (double-array n)
          alias (int-array n)
          small (ArrayDeque.)
          large (ArrayDeque.)]

      (dotimes [i n]
        (if (< (aget scaled i) 1.0)
          (.push small (int i))
          (.push large (int i))))

      (while (and (not (.isEmpty small)) (not (.isEmpty large)))
        (let [l (int (.pop small))
              g (int (.pop large))]
          (aset prob l (aget scaled l))
          (aset alias l g)
          (aset scaled g (- (+ (aget scaled g) (aget scaled l)) 1.0))
          (if (< (aget scaled g) 1.0)
            (.push small (int g))
            (.push large (int g)))))

      (while (not (.isEmpty large))
        (aset prob (int (.pop large)) 1.0))
      (while (not (.isEmpty small))
        (aset prob (int (.pop small)) 1.0))

      (fn sample-weighting [^Random random]
        (let [i (.nextInt random n)]
          (aget items (if (< (.nextDouble random) (aget prob i)) i (aget alias i))))))))

(defn random-seq [worker opts f & args]
  (let [{:keys [min, max, unique]} opts]
    (->> (repeatedly #(apply f worker args))
         (take (+ min (.nextInt (rng worker) (- max min))))
         ((if unique distinct identity)))))

(defn random-str
  ([worker] (random-str worker 1 100))
  ([worker min-len max-len]
   (let [random (rng worker)
         len (max 0 (+ min-len (.nextInt random max-len)))
         buf (byte-array (* 2 len))
         _ (.nextBytes random buf)]
     (.toString (BigInteger. 1 buf) 16))))

(defn random-nth [worker coll]
  (when (seq coll)
    (let [idx (.nextInt (rng worker) (count coll))]
      (nth coll idx nil))))

(defn random-bool [worker]
  (.nextBoolean (rng worker)))

(def kb 1024)
(def mb (* kb 1024))
(def gb (* mb 1024))

(defn get-system-info []
  (let [si (SystemInfo.)
        os (.getOperatingSystem si)
        os-version (.getVersionInfo os)
        hardware (.getHardware si)
        cpu (.getProcessor hardware)]
    {:jre (System/getProperty "java.vendor.version")
     :java-opts (str/join " " (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
     :max-heap (format "%sMB" (quot (.maxMemory (Runtime/getRuntime)) mb))
     :arch (System/getProperty "os.arch")
     :os (->> [(.getFamily os) (.getCodeName os-version) (.getVersion os-version)]
              (remove str/blank?)
              (str/join " "))
     :memory (format "%sGB" (quot (.getTotal (.getMemory hardware)) gb))
     :cpu (format "%s, %s cores, %.2fGHZ max"
                  (.getName (.getProcessorIdentifier cpu))
                  (.getPhysicalProcessorCount cpu)
                  (double (/ (.getMaxFreq cpu) 1e9)))}))

(defn log-report [{:keys [bench-id jvm-id ^Writer bench-log-wrt] :as _worker} report]
  (let [log-record (json/write-str (assoc report :bench-id bench-id :jvm-id jvm-id))]
    (println log-record)
    (when bench-log-wrt
      (binding [*out* bench-log-wrt]
        (println log-record)
        (.flush bench-log-wrt)))))

(def ^:dynamic *registry* nil)

(def percentiles [0.75 0.85 0.95 0.98 0.99 0.999])

(defn- get-transaction-metrics
  "Extract transaction metrics from the registry. Returns a map of transaction name to metrics.
   Filters out internal metrics (those with dots in the name like jvm.gc.pause, query.timer, etc.)"
  []
  (when *registry*
    (->> (.getMeters *registry*)
         (filter #(instance? Timer %))
         (keep (fn [^Timer timer]
                 (let [id (.getId timer)
                       name (.getName id)]
                   ;; Only include benchmark transactions (no dots in name)
                   (when-not (str/includes? name ".")
                     [name {:count (.count timer)
                            :total-time-ms (/ (.totalTime timer TimeUnit/MILLISECONDS) 1.0)
                            :mean-ms (/ (.mean timer TimeUnit/MILLISECONDS) 1.0)
                            :max-ms (/ (.max timer TimeUnit/MILLISECONDS) 1.0)}]))))
         (into {}))))

(defn wrap-stage [f {:keys [stage setup?]}]
  (fn instrumented-stage [worker]
    (if setup?
      (log/info "Starting stage:" stage "(setup)")
      (log/info "Starting stage:" stage))
    (let [start-ms (System/currentTimeMillis)]
      (f worker)
      (let [elapsed-ms (- (System/currentTimeMillis) start-ms)]
        (when setup?
          (swap! (:setup-time-ms worker) + elapsed-ms))
        (log-report worker {:stage stage
                            :time-taken-ms elapsed-ms
                            :setup setup?}))
      (log/info "Done stage:" stage))))

(defn wrap-transaction [f {:keys [transaction labels]}]
  (let [timer-delay (delay
                      (when *registry*
                        (let [timer (Timer/builder (name transaction))]
                          (doseq [[^String k ^String v] labels]
                            (.tag timer k v))
                          (-> timer
                              (.publishPercentiles (double-array percentiles))
                              (.maximumExpectedValue (Duration/ofHours 8))
                              (.minimumExpectedValue (Duration/ofNanos 1))
                              (.register *registry*)))))]
    (fn instrumented-transaction [worker]
      (if-some [^Timer timer @timer-delay]
        (.recordCallable timer ^Callable (fn [] (f worker)))
        (f worker)))))

(defn wrap-task [f task]
  (let [{:keys [stage, transaction]} task]
    (cond
      stage (wrap-stage f task)
      transaction (wrap-transaction f task)
      :else f)))

(defn- lift-f [f]
  (if (vector? f) #(apply (first f) % (rest f)) f))

(defn- compile-task [{:keys [t], :as task}]
  (-> (case t
        nil (constantly nil)

        :do (let [{:keys [tasks]} task
                  fns (mapv compile-task tasks)]
              (fn run-do [worker]
                (doseq [f fns]
                  (f worker))))

        :call (let [{:keys [f]} task]
                (lift-f f))

        :pool (let [{:keys [^Duration duration ^Duration think ^Duration join-wait thread-count pooled-task]} task
                    think-ms (.toMillis (or think Duration/ZERO))
                    f (compile-task pooled-task)

                    executor (Executors/newFixedThreadPool thread-count (util/->prefix-thread-factory "benchy"))

                    thread-loop (fn run-pool-thread-loop [worker]
                                  (loop [wait-until (+ (current-timestamp-ms worker) (.toMillis duration))]
                                    (f worker)
                                    (when (< (current-timestamp-ms worker) wait-until)
                                      ;; Sleep only the remaining time (or think time, whichever is less)
                                      (let [remaining-ms (- wait-until (current-timestamp-ms worker))
                                            sleep-ms (min think-ms remaining-ms)]
                                        (when (pos? sleep-ms)
                                          (Thread/sleep sleep-ms))
                                        (recur wait-until)))))

                    start-thread (fn [root-worker _i]
                                   (let [bindings (get-thread-bindings)
                                         worker (assoc root-worker :random (Random. (.nextLong (rng root-worker))))]
                                     (.submit executor ^Runnable (fn []
                                                                   (push-thread-bindings bindings)
                                                                   (-> worker
                                                                       (assoc :thread-name (.getName (Thread/currentThread)))
                                                                       thread-loop)))))]

                (fn run-pool [worker]
                  (let [futures (mapv #(start-thread worker %) (range thread-count))]
                    (Thread/sleep (.toMillis duration))
                    (.shutdown executor)
                    (when-not (.awaitTermination executor (.toMillis join-wait) TimeUnit/MILLISECONDS)
                      (log/warn "Pool threads did not stop within join-wait, forcing interruption")
                      (.shutdownNow executor)
                      (when-not (.awaitTermination executor 30 TimeUnit/SECONDS)
                        (log/warn "Pool threads still running after forced shutdown - will stop when SUT closes")))
                    (doseq [f futures]
                      (try
                        (deref f 5 TimeUnit/SECONDS) ; will throw ExecutionException if the Runnable raised
                        (catch java.util.concurrent.TimeoutException _
                          (log/warn "Worker thread did not complete in time, skipping"))
                        (catch java.util.concurrent.ExecutionException e
                          (let [cause (.getCause e)]
                            (when (instance? OutOfMemoryError cause)
                              (log/error cause "OutOfMemoryError in worker thread - forcing JVM exit")
                              (System/exit 1))
                            ;; Log but don't rethrow - connection errors during shutdown are expected
                            (if (or (instance? java.sql.SQLException cause)
                                    (re-find #"connection.*closed" (str (.getMessage cause))))
                              (log/debug cause "Expected connection error during shutdown")
                              (log/error cause "Benchmark worker failed in :pool" {:task task})))))))))

        :concurrently (let [{:keys [^Duration duration, ^Duration join-wait, thread-tasks]} task
                            thread-task-fns (mapv compile-task thread-tasks)

                            executor (Executors/newFixedThreadPool (count thread-tasks) (util/->prefix-thread-factory "benchy"))

                            start-thread (fn [root-worker _i f]
                                           (let [bindings (get-thread-bindings)
                                                 worker (assoc root-worker :random (Random. (.nextLong (rng root-worker))))]
                                             (.submit executor ^Runnable (fn [] (push-thread-bindings bindings)
                                                                           (-> worker
                                                                               (assoc :thread-name (.getName (Thread/currentThread)))
                                                                               f)))))]
                        (fn run-concurrently [worker]
                          (let [futures (mapv #(start-thread worker %1 %2) (range (count thread-task-fns)) thread-task-fns)]
                            (Thread/sleep (.toMillis duration))
                            (.shutdown executor)
                            (when-not (.awaitTermination executor (.toMillis join-wait) TimeUnit/MILLISECONDS)
                              (log/warn "Threads did not stop within join-wait, forcing interruption")
                              (.shutdownNow executor)
                              (when-not (.awaitTermination executor 30 TimeUnit/SECONDS)
                                (log/warn "Threads still running after forced shutdown - will stop when SUT closes")))
                            (doseq [f futures]
                              (try
                                (deref f 5 TimeUnit/SECONDS) ; will throw ExecutionException if the Runnable raised
                                (catch java.util.concurrent.TimeoutException _
                                  (log/warn "Worker thread did not complete in time, skipping"))
                                (catch java.util.concurrent.ExecutionException e
                                  (let [cause (.getCause e)]
                                    (when (instance? OutOfMemoryError cause)
                                      (log/error cause "OutOfMemoryError in worker thread - forcing JVM exit")
                                      (System/exit 1))
                                    ;; Log but don't rethrow - connection errors during shutdown are expected
                                    (if (or (instance? java.sql.SQLException cause)
                                            (re-find #"connection.*closed" (str (.getMessage cause))))
                                      (log/debug cause "Expected connection error during shutdown")
                                      (log/error cause "Benchmark worker failed in :concurrently" {:task task})))))))))

        :pick-weighted (let [{:keys [choices]} task
                             sample-fn (weighted-sample-fn (mapv (fn [[weight task]] [(compile-task task) weight]) choices))]
                         (if (empty? choices)
                           (constantly nil)
                           (fn run-pick-weighted [worker]
                             (let [f (sample-fn (rng worker))]
                               (f worker)))))

        :freq-job (let [{:keys [^Duration duration,
                                ^Duration freq,
                                job-task]} task
                        f (compile-task job-task)
                        duration-ms (.toMillis (or duration Duration/ZERO))
                        freq-ms (.toMillis (or freq Duration/ZERO))]
                    (fn run-freq-job [worker]
                      (loop [wait-until (+ (current-timestamp-ms worker) duration-ms)]
                        (f worker)
                        (when (< (current-timestamp-ms worker) wait-until)
                          ;; Sleep only the remaining time (or freq time, whichever is less)
                          (let [remaining-ms (- wait-until (current-timestamp-ms worker))
                                sleep-ms (min freq-ms remaining-ms)]
                            (when (pos? sleep-ms)
                              (Thread/sleep sleep-ms))
                            (recur wait-until)))))))

      (wrap-task task)))

(defn- get-env [k]
  (let [v (System/getenv k)]
    (when-not (str/blank? v) v)))

(defn- get-run-context []
  (->> {"GIT_SHA" :git-sha
        "GIT_BRANCH" :git-branch
        "GITHUB_REPOSITORY" :github-repo
        "GITHUB_RUN_ID" :github-run-id}
       (keep (fn [[env-key map-key]]
               (when-let [v (get-env env-key)]
                 [map-key v])))
       (into {})))

(defn compile-benchmark
  "Compile a benchmark map into a function `(fn [node] ...)` that runs every task
   against `node`, instrumenting stages/transactions and emitting init + summary
   reports.

   Benchmark keys: `:title`, `:benchmark-type`, `:seed` (default 0), `:parameters`,
   `:->state` (a fn or `[fn & args]` merged into the worker), `:timeout`,
   `:bench-log-file`, and `:tasks`."
  [{:keys [bench-log-file title benchmark-type seed ->state parameters timeout], :or {seed 0}, :as benchmark}]
  (let [fns (mapv compile-task (:tasks benchmark))]
    (fn run-benchmark [node]
      (letfn [(execute []
                (util/with-open [bench-log-wrt (when bench-log-file (io/writer bench-log-file))]
                  (let [worker (into (assoc (->Worker node (Random. seed) (Clock/systemUTC) (random-uuid) (System/getProperty "user.name") bench-log-wrt)
                                            :setup-time-ms (atom 0))
                                     (cond
                                       (vector? ->state) (apply (first ->state) (rest ->state))
                                       (fn? ->state) (->state)))
                        start-ms (System/currentTimeMillis)
                        run-context (get-run-context)
                        system-info (get-system-info)]
                    (log-report worker (merge {:benchmark title
                                               :benchmark-type benchmark-type
                                               :stage "init"
                                               :parameters parameters
                                               :system system-info}
                                              run-context
                                              (when timeout {:timeout timeout})))

                    (doseq [f fns]
                      (f worker))

                    (let [total-ms (- (System/currentTimeMillis) start-ms)
                          setup-ms @(:setup-time-ms worker)
                          benchmark-ms (- total-ms setup-ms)
                          tx-metrics (get-transaction-metrics)
                          tx-count (reduce + 0 (map :count (vals tx-metrics)))
                          ;; Calculate throughput using configured duration if available
                          duration-secs (when-let [^Duration d (:duration parameters)]
                                          (/ (.toMillis d) 1000.0))
                          throughput (when (and duration-secs (pos? duration-secs))
                                       (/ tx-count duration-secs))]
                      (log-report worker (cond-> {:benchmark title
                                                  :benchmark-type benchmark-type
                                                  :stage "summary"
                                                  :parameters parameters
                                                  :system system-info
                                                  :time-taken-ms benchmark-ms}
                                           (pos? setup-ms) (assoc :setup-time-ms setup-ms
                                                                  :total-time-ms total-ms)
                                           (pos? tx-count) (assoc :transaction-count tx-count
                                                                  :transactions tx-metrics)
                                           throughput (assoc :throughput throughput)
                                           timeout (assoc :timeout timeout)
                                           (seq run-context) (merge run-context)))
                      ;; Human-readable summary
                      (let [secs (/ benchmark-ms 1000.0)
                            mins (int (/ secs 60))
                            remaining-secs (- secs (* mins 60))]
                        (if (>= mins 1)
                          (log/infof "Benchmark Time: %dm %.1fs%s"
                                     mins remaining-secs
                                     (if (pos? setup-ms) (format " (excludes %.1fs setup)" (/ setup-ms 1000.0)) ""))
                          (log/infof "Benchmark Time: %.1fs%s"
                                     secs
                                     (if (pos? setup-ms) (format " (excludes %.1fs setup)" (/ setup-ms 1000.0)) "")))
                        (when (pos? tx-count)
                          (let [sorted-txs (->> tx-metrics (sort-by (comp :count val) >))
                                name-width (max 12 (apply max (map (comp count key) sorted-txs)))
                                fmt-str (str "  %-" name-width "s  %6d txns  avg %8.1fms  max %8.1fms  total %8.1fs")]
                            (log/infof "Transaction Stats (%d total%s):"
                                       tx-count
                                       (if throughput (format ", %.1f tx/sec" throughput) ""))
                            (doseq [[tx-name {:keys [count total-time-ms mean-ms max-ms]}] sorted-txs]
                              (log/infof fmt-str tx-name count mean-ms max-ms (/ total-time-ms 1000.0))))))))))]
        (run-with-timeout execute timeout)))))

(defmulti cli-flags
  "Extra clojure.tools.cli flag specs for a benchmark type (dispatch on the type
   keyword). Default: none."
  identity
  :default ::default)

(defmethod cli-flags ::default [_] [])

(defmulti ->benchmark
  "Build a benchmark map (see `compile-benchmark`) for `benchmark-type` from the
   parsed CLI `opts`. The returned map may include a `:->sut` (see `run-benchmark`)."
  #_{:clj-kondo/ignore [:unused-binding]}
  (fn [benchmark-type opts]
    benchmark-type)
  :default ::default)

(defn run-benchmark
  "Run `benchmark` (a map, typically from `->benchmark`).

   Requires a `:->sut` function (in `benchmark` or in `opts`) that, given the
   opts, returns a map:
     {:node     <object passed to every task fn as (:node worker)>  ; required
      :registry <optional io.micrometer...MeterRegistry>
      :close    <optional 0-arg teardown fn>}

   Registry resolution: the SUT's `:registry` if provided; else, when
   `:metrics-port` is a positive int, a PrometheusMeterRegistry served at
   `/metrics`; else a SimpleMeterRegistry. The harness closes any registry/server
   it creates and calls `:close` afterwards."
  [benchmark {:keys [bench-log-file timeout metrics-port] :as opts}]
  (let [->sut (or (:->sut opts) (:->sut benchmark)
                  (throw (ex-info "run-benchmark requires a :->sut function (in the benchmark map or opts)" {})))
        benchmark-fn (compile-benchmark (-> benchmark
                                            (assoc :bench-log-file bench-log-file)
                                            (assoc :timeout timeout)))
        sut (->sut opts)]
    (try
      (let [sut-registry (:registry sut)
            use-prom? (and (nil? sut-registry) metrics-port (pos? (long metrics-port)))
            ^MeterRegistry registry (or sut-registry
                                        (if use-prom?
                                          (metrics/prometheus-registry)
                                          (SimpleMeterRegistry.)))
            server (when use-prom? (metrics/start-metrics-server registry (long metrics-port)))]
        (try
          (binding [*registry* registry]
            (benchmark-fn (:node sut)))
          (finally
            (when server (metrics/stop-metrics-server server))
            (when (nil? sut-registry) (.close registry)))))
      (catch Throwable t
        (if @shutting-down?
          (log/warn t "Error during shutdown (ignored):")
          (throw t)))
      (finally
        (when-let [close (:close sut)] (close))))))

(defn- parse-duration-arg [s]
  (Duration/parse s))

(def ^:private default-cli-flags
  [[nil "--node-dir NODE_DIR"
    "Directory for the system-under-test to use (passed through to your :->sut)."
    :parse-fn util/->path]

   [nil "--no-load" "Don't run any load phases (use with an existing directory)."
    :id :no-load?]

   [nil "--bench-log-file BENCH_LOG_FILE" "File to append benchmark results to, as JSON."
    :id :bench-log-file
    :parse-fn io/file]

   [nil "--timeout DURATION"
    "Maximum wall-clock runtime before aborting the benchmark (ISO-8601). If omitted, no timeout is applied."
    :id :timeout
    :parse-fn parse-duration-arg
    :validate [#(not (neg? (.toMillis ^Duration %))) "Timeout must be >= PT0S"]]

   [nil "--metrics-port PORT"
    "Port for the Prometheus /metrics HTTP endpoint (0 disables)."
    :id :metrics-port
    :default 8080
    :parse-fn #(Integer/parseInt %)]

   ["-h" "--help"]])

(defn -main [benchmark-type & args]
  (util/install-uncaught-exception-handler!)

  ;; Benchmarks live in `<prefix>.<type>` namespaces (default prefix `benchy`),
  ;; each implementing `->benchmark` (and optionally `cli-flags`).
  (let [ns-prefix (or (System/getProperty "benchy.ns-prefix")
                      (System/getenv "BENCHY_NS_PREFIX")
                      "benchy")]
    (require (symbol (str ns-prefix "." benchmark-type)))

    (let [bt (keyword benchmark-type)
          {:keys [options errors summary]} (cli/parse-opts args (concat (cli-flags bt) default-cli-flags))]
      (cond
        (seq errors) (binding [*out* *err*]
                       (doseq [error errors]
                         (println error))
                       (System/exit 2))

        (:help options) (binding [*out* *err*]
                          (println summary)
                          (System/exit 0))

        :else (let [main-thread (Thread/currentThread)]
                (-> (Runtime/getRuntime)
                    (.addShutdownHook (Thread. ^Runnable
                                               (fn []
                                                 (log/info "Received shutdown signal, initiating graceful shutdown...")
                                                 (reset! shutting-down? true)
                                                 (let [shutdown-ms 10000]
                                                   (.interrupt main-thread)
                                                   (.join main-thread shutdown-ms)
                                                   (if (.isAlive main-thread)
                                                     (do
                                                       (log/warn "could not stop benchmark cleanly after" shutdown-ms "ms, forcing exit")
                                                       (-> (Runtime/getRuntime) (.halt 1)))
                                                     (log/info "Benchmark stopped."))))
                                               "benchy.shutdown-hook-thread")))

                (try
                  (run-benchmark (->benchmark bt options) options)
                  (catch Throwable t
                    (if @shutting-down?
                      (log/warn t "Error during shutdown (ignored):")
                      (do
                        (when (instance? InterruptedException t)
                          (.interrupt (Thread/currentThread)))
                        (log/error t "Benchmark failed:")
                        (throw t))))
                  (finally
                    (reset! shutting-down? true)))))))

  (shutdown-agents))
