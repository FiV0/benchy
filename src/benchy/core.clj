(ns benchy.core
  "Composable, database-independent benchmark tasks."
  (:require [benchy.report :as report]
            [benchy.runtime :as runtime])
  (:import (com.google.common.collect MinMaxPriorityQueue)
           (java.time Clock Duration)
           (java.util Comparator Random)
           (java.util.concurrent CancellationException)
           (java.util.concurrent.atomic AtomicLong)))

(defn current-timestamp ^java.time.Instant [worker]
  (.instant ^Clock (:clock worker)))

(defn rng ^java.util.Random [worker] (:random worker))

(defn current-timestamp-ms ^long [worker] (.millis ^Clock (:clock worker)))

(defn sample-gaussian [worker ^AtomicLong !counter]
  (let [v (.get !counter)]
    (when (pos? v)
      (-> (long (.nextGaussian (rng worker) (* v 0.5) (/ v 6.0)))
          (max 0)
          (min (dec v))))))

(defn sample-flat [worker, ^AtomicLong !counter]
  (let [v (.get !counter)]
    (when (pos? v)
      (.nextLong (rng worker) v))))

(defn inc-count! [^AtomicLong !counter]
  (.getAndIncrement !counter))

(defn set-count! [^AtomicLong !counter, ^long v]
  (.set !counter (inc v)))

(defn weighted-sample-fn
  "Aliased random sampler:

  https://www.peterstefek.me/alias-method.html

  Given a seq of [item weight] pairs, return a function who when given a Random will return an item according to the weight distribution."
  [weighted-items]
  (when (or (some (fn [[_ weight]]
                   (not (and (number? weight) (Double/isFinite (double weight))
                             (not (neg? weight))))) weighted-items)
            (and (seq weighted-items)
                 (let [total (reduce + 0.0 (map (comp double second) weighted-items))]
                   (not (and (Double/isFinite total) (pos? total))))))
    (throw (ex-info "Weights must be finite, non-negative, with a positive total"
                    {:benchy/type :invalid-weights})))
  (case (count weighted-items)
    0 (constantly nil)
    1 (constantly (ffirst weighted-items))

    (let [total (reduce + 0.0 (map (comp double second) weighted-items))
          len (count weighted-items)
          normalized-items (mapv (fn [[item weight]] [item (* len (/ (double weight) total))]) weighted-items)
          pq (doto (.create (MinMaxPriorityQueue/orderedBy ^Comparator (fn [[_ w] [_ w2]] (compare w w2))))
               (.addAll normalized-items))
          parts (object-array len)]

      (dotimes [i len]
        (let [[smallest small-weight] (.pollFirst pq)
              overfill (- 1.0 small-weight)]
          (if (and (pos? overfill) (not (.isEmpty pq)))
            (let [[largest large-weight] (.pollLast pq)
                  new-weight (- large-weight overfill)]
              (.add pq [largest new-weight])
              (aset parts i [small-weight smallest largest]))
            (aset parts i [1.0 smallest smallest]))))

      ^{:table parts}
      (fn sample-weighting [^Random random]
        (let [i (.nextInt random len)
              [split small large] (aget parts i)]
          (if (< (.nextDouble random) (double split)) small large))))))

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

(defn log-report
  "Write a custom event to this run's optional JSON Lines report."
  [worker event]
  (let [{:keys [lock data] :as report} (:benchy/report worker)]
    (locking lock
      (when-not (:closed? @data)
        (report/emit! report event)))))

(defn get-system-info [] (report/system-info))

(defn- invalid! [path message]
  (throw (ex-info message {:benchy/type :invalid-benchmark :path path})))

(defn- duration! [path duration]
  (when-not (and (instance? Duration duration) (not (.isNegative ^Duration duration)))
    (invalid! path "Expected a non-negative java.time.Duration"))
  (try (.toNanos ^Duration duration)
       (catch ArithmeticException _ (invalid! path "Duration exceeds nanosecond range"))))

(defn- callable? [f] (or (fn? f) (var? f)))

(defn- lift-f [path f]
  (cond
    (callable? f) f
    (and (vector? f) (callable? (first f))) #(apply (first f) % (rest f))
    :else (invalid! path "Expected a function or [function & arguments]")))

(defn- wrap-transaction [f {:keys [transaction labels]}]
  (fn [worker]
    (let [start (System/nanoTime)
          error (volatile! nil)]
      (try
        (f worker)
        (catch Throwable e
          (vreset! error e)
          (if (or (:benchy/setup? worker)
                  (not (instance? Exception e))
                  (instance? InterruptedException e)
                  (instance? CancellationException e))
            (throw e)
            (do
              (runtime/check-interrupted! worker)
              nil)))
        (finally
          (report/record-transaction! (:benchy/report worker) transaction (or labels {})
                                      (:benchy/setup? worker) (- (System/nanoTime) start) @error))))))

(defn- wrap-stage [f {:keys [stage setup?]}]
  (fn [worker]
    (let [report (:benchy/report worker)
          start (report/stage-start! report stage setup?)]
      (try
        (f (cond-> worker setup? (assoc :benchy/setup? true)))
        (finally (report/stage-end! report stage setup? start))))))

(declare compile-task)

(defn- compile-tasks [path tasks]
  (when-not (sequential? tasks) (invalid! path "Expected a sequence of tasks"))
  (mapv #(compile-task (conj path %1) %2) (range) tasks))

(defn- compile-task [path task]
  (if (nil? task)
    (constantly nil)
    (do
      (when-not (map? task) (invalid! path "Expected a task map"))
      (doseq [k [:stage :transaction] :when (contains? task k)]
        (when-not (or (keyword? (get task k)) (string? (get task k)))
          (invalid! (conj path k) "Expected a keyword or string")))
      (when (and (contains? task :setup?) (not (boolean? (:setup? task))))
        (invalid! (conj path :setup?) "Expected a boolean"))
      (when (contains? task :labels)
        (when-not (and (map? (:labels task))
                       (every? (fn [[k v]] (and (string? k) (string? v))) (:labels task)))
          (invalid! (conj path :labels) "Labels must map strings to strings")))
      (doseq [k [:duration :think :freq :join-wait] :when (contains? task k)]
        (duration! (conj path k) (get task k)))
      (when (#{:pool :concurrently :freq-job} (:t task))
        (duration! (conj path :duration) (:duration task)))
      (let [f (case (:t task)
                :call (lift-f (conj path :f) (:f task))
                :do (let [fs (compile-tasks (conj path :tasks) (:tasks task))]
                      (fn [worker] (doseq [f fs] (f worker))))
                :pick-weighted
                (let [choices (:choices task)]
                  (when-not (and (sequential? choices)
                                 (every? #(and (vector? %) (= 2 (count %))) choices))
                    (invalid! (conj path :choices) "Expected [weight task] choices"))
                  (let [sampler (weighted-sample-fn
                                 (mapv (fn [i [weight child]]
                                         [(compile-task (conj path :choices i 1) child) weight])
                                       (range) choices))]
                    (fn [worker] (when-let [f (sampler (rng worker))] (f worker)))))
                :freq-job
                (let [f (compile-task (conj path :job-task) (:job-task task))]
                  (when-not (contains? task :job-task) (invalid! path "Missing :job-task"))
                  (fn [worker] (runtime/repeat-task! worker (:duration task) (or (:freq task) Duration/ZERO) f)))
                :pool
                (let [n (:thread-count task)
                      f (compile-task (conj path :pooled-task) (:pooled-task task))]
                  (when-not (and (integer? n) (pos? n) (<= n Integer/MAX_VALUE))
                    (invalid! (conj path :thread-count) "Expected a positive thread count"))
                  (when-not (contains? task :pooled-task) (invalid! path "Missing :pooled-task"))
                  (fn [worker]
                    (runtime/parallel! worker task
                                       (vec (repeat n #(runtime/repeat-task! % (:duration task)
                                                                             (or (:think task) Duration/ZERO) f))))))
                :concurrently
                (let [fs (compile-tasks (conj path :thread-tasks) (:thread-tasks task))]
                  (fn [worker] (runtime/parallel! worker task fs)))
                (invalid! (conj path :t) "Unknown task type"))
            f (cond-> f
                (:transaction task) (wrap-transaction task)
                (or (:stage task) (:setup? task)) (wrap-stage task))]
        (fn [worker]
          (runtime/check-interrupted! worker)
          (f worker))))))

(defn- initial-state [initializer]
  (let [state (cond
                (nil? initializer) {}
                (vector? initializer) (apply (first initializer) (rest initializer))
                :else (initializer))]
    (when-not (map? state) (invalid! [:->state] "State initializer must return a map"))
    state))

(defn- propagate! [error result]
  (if (or (instance? Error error) (instance? InterruptedException error))
    (do
      (.addSuppressed ^Throwable error (ex-info "Partial benchmark result" {:partial-result result}))
      (when (instance? InterruptedException error) (.interrupt (Thread/currentThread)))
      (throw error))
    (throw (ex-info "Benchmark failed" (assoc (ex-data error) :partial-result result) error))))

(defn compile-benchmark
  "Validate a benchmark map and return a reusable function of zero args or a context map.
   Every invocation creates fresh state, random generators, metrics, and executors."
  [{:keys [seed ->state] :or {seed 0} :as benchmark}]
  (when-not (map? benchmark) (invalid! [] "Expected a benchmark map"))
  (when-not (and (integer? seed) (<= Long/MIN_VALUE seed Long/MAX_VALUE))
    (invalid! [:seed] "Seed must fit in a signed long"))
  (doseq [k [:timeout :join-wait] :when (some? (get benchmark k))]
    (duration! [k] (get benchmark k)))
  (when (some? ->state) (lift-f [:->state] ->state))
  (let [fs (compile-tasks [:tasks] (:tasks benchmark))]
    (fn run
      ([] (run {}))
      ([context]
       (when-not (map? context) (invalid! [:context] "Context must be a map"))
       (let [report (report/open-report (assoc benchmark :seed seed))
             worker {:context context :random (Random. (long seed)) :clock (Clock/systemUTC)
                     :bench-id (get-in report [:metadata :bench-id])
                     :benchy/report report :benchy/cancelled? (atom false)}]
         (try
           (report/emit! report {:event :init})
           (try
             (runtime/execute! worker benchmark
                               (fn [worker]
                                 (let [worker (assoc worker :state (initial-state ->state))]
                                   (doseq [f fs] (f worker)))))
             (catch Throwable error
               (let [status (cond
                              (#{:timeout :task-timeout} (:benchy/type (ex-data error))) :timed-out
                              (or (instance? InterruptedException error)
                                  (instance? CancellationException error)) :cancelled
                              :else :failed)]
                 (propagate! error (report/finish! report status error)))))
           (report/finish! report :completed nil)
           (finally (report/close! report))))))))

(defn run-benchmark
  "Run a benchmark with an optional caller-owned context map and return its result."
  ([benchmark] ((compile-benchmark benchmark)))
  ([benchmark context] ((compile-benchmark benchmark) context)))
