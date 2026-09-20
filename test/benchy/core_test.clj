(ns benchy.core-test
  (:require [benchy.core :as bench]
            [benchy.report :as report]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]])
  (:import (java.time Duration)
           (java.util.concurrent CountDownLatch CyclicBarrier TimeUnit)))

(use-fixtures :each (fn [test] (with-redefs [report/system-info (constantly {:test true})] (test))))

(defn call [f] {:t :call :f f})
(defn measured [f] (assoc (call f) :transaction :operation))
(defn caught [f] (try (f) nil (catch Throwable t t)))
(defn ms [n] (Duration/ofMillis n))

(deftest sequential-tasks-context-and-state
  (let [events (atom [])
        state-count (atom 0)
        resource (Object.)
        run (bench/compile-benchmark
             {:->state [(fn [n] (swap! state-count inc) {:n (atom n)}) 10]
              :tasks [nil {:t :do :stage :work
                           :tasks [(call (fn [{:keys [context state]}]
                                           (is (identical? resource (:resource context)))
                                           (swap! events conj (swap! (:n state) inc))))
                                   {:t :call :f [(fn [_ x] (swap! events conj x)) :second]}]}]})]
    (is (= :completed (:status (run {:resource resource}))))
    (is (= :completed (:status (run {:resource resource}))))
    (is (= [11 :second 11 :second] @events))
    (is (= 2 @state-count))))

(deftest counts-errors-and-continues-with-bounded-details
  (let [calls (atom 0)
        result (bench/run-benchmark
                {:tasks (vec (repeat 30 (measured (fn [_]
                                                   (when (odd? (swap! calls inc))
                                                     (throw (ex-info "expected" {})))))))})
        tx (first (:transactions result))]
    (is (= 30 @calls (:transaction-count result) (:count tx)))
    (is (= 15 (:error-count result) (:error-count tx) (:success-count tx)))
    (is (= 10 (count (:errors result))))
    (is (= :completed-with-errors (:status result)))
    (is (contains? tx :p99-ms))
    (is (< (Math/abs (- 1.0 (/ (:throughput result)
                               (/ 15 (/ (:time-taken-ms result) 1000.0))))) 1e-12))))

(deftest labels-and-run-metrics-are-isolated
  (let [run (bench/compile-benchmark
             {:tasks [(assoc (measured identity) :transaction :query.read :labels {"db" "a"})
                      (assoc (measured identity) :transaction :query.read :labels {"db" "b"})]})]
    (doseq [result [(run) (run)]]
      (is (= 2 (:transaction-count result)))
      (is (= #{[{"db" "a"} 1] [{"db" "b"} 1]}
             (set (map (juxt :labels :count) (:transactions result))))))))

(deftest setup-excludes-overlapping-nested-time
  (let [result (bench/run-benchmark
                {:tasks [{:t :do :stage :setup :setup? true
                          :tasks [{:t :do :stage :nested :setup? true
                                   :tasks [(measured identity)]}]}
                         (measured identity)]})]
    (is (<= 0 (:setup-time-ms result) (:total-time-ms result)))
    (is (< (Math/abs (- (:total-time-ms result)
                        (+ (:setup-time-ms result) (:time-taken-ms result)))) 0.00001))
    (is (= #{true false} (set (map :setup? (:transactions result)))))
    (is (= 2 (:transaction-count result)))
    (is (< (Math/abs (- 1.0 (/ (:throughput result)
                               (/ 1 (/ (:time-taken-ms result) 1000.0))))) 1e-12))))

(deftest fatal-task-and-setup-errors-have-partial-results
  (doseq [task [(call (fn [_] (throw (ex-info "broken" {:reason :test}))))
                (assoc (measured (fn [_] (throw (ex-info "broken" {:reason :test})))) :setup? true :stage :setup)]]
    (let [after (atom false)
          error (caught #(bench/run-benchmark {:tasks [task (call (fn [_] (reset! after true)))]}))]
      (is (= :test (:reason (ex-data error))))
      (is (= "broken" (.getMessage (.getCause error))))
      (is (= :failed (get-in (ex-data error) [:partial-result :status])))
      (is (false? @after)))))

(deftest invalid-definitions-fail-before-state-or-tasks
  (doseq [task [{:t :unknown} {:t :call :f 1}
                {:t :pool :duration (ms 1) :thread-count 0 :pooled-task nil}
                {:t :freq-job :duration (ms -1) :job-task nil}
                {:t :pool :duration (ms 1) :thread-count 1}
                {:t :call :f identity :labels {:db 1}}
                {:t :pick-weighted :choices [[-1 nil]]}]]
    (is (instance? clojure.lang.ExceptionInfo (caught #(bench/compile-benchmark {:tasks [task]})))))
  (is (thrown? clojure.lang.ExceptionInfo (bench/compile-benchmark {:tasks [] :timeout (ms -1)}))))

(deftest reports-json-lines-without-writing-stdout
  (let [file (java.io.File/createTempFile "benchy-test" ".jsonl")]
    (try
      (is (= "" (with-out-str
                   (bench/run-benchmark {:title "report" :parameters {:duration (ms 10)}
                                         :bench-log-file file
                                         :tasks [(assoc (measured identity) :stage :work)]}))))
      (let [events (mapv #(json/read-str % :key-fn keyword) (str/split-lines (slurp file)))]
        (is (= ["init" "stage-start" "stage-end" "summary"] (mapv :event events)))
        (is (every? #(= "PT0.01S" (get-in % [:parameters :duration])) events))
        (is (= 1 (count (set (map :bench-id events)))))
        (is (= "completed" (:status (peek events)))))
      (finally (.delete file)))))

(def ^:dynamic *marker* :unbound)

(defn launch [f]
  (let [result (promise)
        thread (doto (Thread. ^Runnable
                             (bound-fn []
                               (try (deliver result {:result (f)})
                                    (catch Throwable t
                                      (deliver result {:error t :interrupted? (.isInterrupted (Thread/currentThread))})))))
                 (.setDaemon true)
                 (.start))]
    {:thread thread :result result}))

(defn stop-launched! [{:keys [^Thread thread]}]
  (.interrupt thread)
  (.join thread 2000))

(deftest simultaneous-runs-have-independent-state-rngs-and-metrics
  (let [barrier (CyclicBarrier. 2)
        run (bench/compile-benchmark
             {:seed 123
              :->state #(hash-map :seen (atom #{}))
              :tasks [(call (fn [_] (.await barrier 3 TimeUnit/SECONDS)))
                      {:t :concurrently :duration (ms 3000)
                       :thread-tasks (mapv (fn [id]
                                             (measured
                                              (fn [{:keys [state context] :as worker}]
                                                (is (= :bound *marker*))
                                                (swap! (:seen state) conj id)
                                                (swap! (:values context) assoc id (.nextLong (bench/rng worker))))))
                                           (range 3))}
                      (call (fn [{:keys [state]}] (is (= #{0 1 2} @(:seen state)))))]})
        a (atom {}) b (atom {})
        runs (binding [*marker* :bound] [(launch #(run {:values a})) (launch #(run {:values b}))])]
    (try
      (doseq [{:keys [result]} runs]
        (let [outcome (deref result 5000 ::timeout)]
          (is (= :completed (get-in outcome [:result :status])) (pr-str outcome))
          (is (= 3 (get-in outcome [:result :transaction-count])))))
      (is (= @a @b))
      (finally (doseq [run runs] (stop-launched! run))))))

(deftest pooled-runs-repeat-count-errors-and-stop-threads
  (let [threads (atom #{})
        calls (atom 0)
        run (bench/compile-benchmark
             {:tasks [{:t :pool :thread-count 3 :duration (ms 80) :think (ms 1)
                       :pooled-task (measured (fn [_]
                                               (swap! threads conj (Thread/currentThread))
                                               (when (odd? (swap! calls inc))
                                                 (throw (Exception. "operation failure")))))}]})]
    (dotimes [_ 2]
      (reset! calls 0)
      (let [result (run)]
        (is (pos? @calls))
        (is (= @calls (:transaction-count result)))
        (is (= (quot (inc @calls) 2) (:error-count result)))))
    (doseq [^Thread thread @threads]
      (.join thread 1000)
      (is (not (.isAlive thread))))))

(deftest zero-duration-repetition-and-empty-choices-do-nothing
  (let [bad (call (fn [_] (throw (Exception. "must not run"))))]
    (is (= :completed
           (:status (bench/run-benchmark
                     {:tasks [{:t :freq-job :duration Duration/ZERO :job-task bad}
                              {:t :pool :duration Duration/ZERO :thread-count 2 :pooled-task bad}
                              {:t :pick-weighted :choices []}
                              {:t :concurrently :duration Duration/ZERO :thread-tasks []}]}))))))

(deftest frequency-job-runs-and-caps-its-delay
  (let [n (atom 0)
        result (bench/run-benchmark
                {:timeout (ms 3000)
                 :tasks [{:t :freq-job :duration (ms 60) :freq (Duration/ofDays 1)
                          :job-task (measured (fn [_] (swap! n inc)))}]})]
    (is (= :completed (:status result)))
    (is (= 1 @n (:transaction-count result)))))

(deftest worker-failure-cancels-siblings
  (let [started (CountDownLatch. 1)
        stopped (promise)
        blocked (CountDownLatch. 1)
        error (caught
               #(bench/run-benchmark
                 {:tasks [{:t :concurrently :duration (ms 3000)
                           :thread-tasks [(call (fn [_]
                                                  (.countDown started)
                                                  (try (.await blocked)
                                                       (finally (deliver stopped true)))))
                                          (call (fn [_]
                                                  (assert (.await started 2 TimeUnit/SECONDS))
                                                  (throw (ex-info "fatal" {:reason :fatal}))))]}]}))]
    (is (= :fatal (:reason (ex-data error))))
    (is (= true (deref stopped 1000 false)))))

(deftest timeout-cancels-nested-pool
  (let [stopped (promise)
        blocked (CountDownLatch. 1)
        thread (atom nil)
        error (caught
               #(bench/run-benchmark
                 {:timeout (ms 500)
                  :tasks [{:t :pool :thread-count 1 :duration (ms 10000)
                           :pooled-task (measured (fn [_]
                                                   (reset! thread (Thread/currentThread))
                                                   (try (.await blocked)
                                                        (finally (deliver stopped true)))))}]}))]
    (is (= :timeout (:benchy/type (ex-data error))))
    (is (= :timed-out (get-in (ex-data error) [:partial-result :status])))
    (is (= true (deref stopped 1000 false)))
    (when-let [^Thread t @thread]
      (.join t 1000)
      (is (not (.isAlive t))))))

(deftest task-timeout-interrupts-an-overrunning-call
  (let [stopped (promise)
        blocked (CountDownLatch. 1)
        error (caught #(bench/run-benchmark
                        {:tasks [{:t :concurrently :duration (ms 40) :join-wait (ms 40)
                                  :thread-tasks [(call (fn [_]
                                                        (try (.await blocked)
                                                             (finally (deliver stopped true)))))]}]}))]
    (is (= :task-timeout (:benchy/type (ex-data error))))
    (is (= true (deref stopped 1000 false)))))

(deftest caller-interruption-is-preserved-and-stops-work
  (let [started (CountDownLatch. 1)
        stopped (promise)
        blocked (CountDownLatch. 1)
        run (launch #(bench/run-benchmark
                      {:tasks [(call (fn [_]
                                       (.countDown started)
                                       (try (.await blocked)
                                            (finally (deliver stopped true)))))]}))]
    (try
      (is (.await started 3 TimeUnit/SECONDS))
      (.interrupt ^Thread (:thread run))
      (let [{:keys [error interrupted?]} (deref (:result run) 3000 {})]
        (is (instance? InterruptedException error))
        (is interrupted?)
        (is (= :cancelled (-> error .getSuppressed first ex-data :partial-result :status))))
      (is (= true (deref stopped 1000 false)))
      (finally (stop-launched! run)))))

(deftest jvm-errors-propagate-without-being-counted-and-continued
  (let [original (AssertionError. "fatal")
        error (caught #(bench/run-benchmark {:tasks [(measured (fn [_] (throw original)))]}))]
    (is (identical? original error))
    (is (= :failed (-> error .getSuppressed first ex-data :partial-result :status)))))

(deftest concurrent-runs-append-complete-records-to-one-file
  (let [file (java.io.File/createTempFile "benchy-concurrent" ".jsonl")
        barrier (CyclicBarrier. 2)
        run (bench/compile-benchmark
             {:bench-log-file file
              :tasks [(call (fn [_] (.await barrier 3 TimeUnit/SECONDS)))
                      {:t :concurrently :duration (ms 3000)
                       :thread-tasks (vec (repeat 4 (measured (fn [worker]
                                                               (dotimes [i 10]
                                                                 (bench/log-report worker {:event :custom :n i}))))))}]})
        runs [(launch run) (launch run)]]
    (try
      (doseq [run runs]
        (is (= :completed (get-in (deref (:result run) 5000 {}) [:result :status]))))
      (let [events (mapv #(json/read-str % :key-fn keyword) (str/split-lines (slurp file)))
            groups (vals (group-by :bench-id events))]
        (is (= 2 (count groups)))
        (doseq [events groups]
          (is (= 42 (count events)))
          (is (= "init" (:event (first events))))
          (is (= "summary" (:event (last events))))
          (is (= 4 (:transaction-count (last events))))))
      (finally
        (doseq [run runs] (stop-launched! run))
        (.delete file)))))

(deftest keyword-and-string-transaction-names-do-not-share-timers
  (let [result (bench/run-benchmark
                {:tasks [(assoc (measured identity) :transaction :op)
                         (assoc (measured identity) :transaction ":op")]})]
    (is (= 2 (:transaction-count result)))
    (is (= [1 1] (mapv :count (:transactions result))))))

(deftest timeout-covers-state-initialization
  (let [stopped (promise)
        blocked (CountDownLatch. 1)
        error (caught #(bench/run-benchmark
                        {:timeout (ms 200)
                         :->state (fn [] (try (.await blocked)
                                             (finally (deliver stopped true))) {})
                         :tasks []}))]
    (is (= :timeout (:benchy/type (ex-data error))))
    (is (= true (deref stopped 1000 false)))))

(deftest timeout-return-is-bounded-for-uncooperative-callbacks
  (let [started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        stopped (promise)
        worker-thread (atom nil)
        file (java.io.File/createTempFile "benchy-late" ".jsonl")
        run (launch #(bench/run-benchmark
                      {:bench-log-file file :timeout (ms 300) :join-wait (ms 20)
                       :tasks [(assoc (measured
                                       (fn [worker]
                                         (reset! worker-thread (Thread/currentThread))
                                         (.countDown started)
                                         (try
                                           (loop []
                                             (when-not (try (.await release) true
                                                            (catch InterruptedException _ false))
                                               (recur)))
                                           (bench/log-report worker {:event :late})
                                           (finally (deliver stopped true)))))
                                      :stage :blocked)]}))]
    (try
      (is (.await started 2 TimeUnit/SECONDS))
      (let [outcome (deref (:result run) 2000 ::timeout)]
        (is (not= ::timeout outcome))
        (is (= :timeout (-> outcome :error ex-data :benchy/type)))
        (is (.isDaemon ^Thread @worker-thread))
        (.countDown release)
        (is (= true (deref stopped 1000 false)))
        (.join ^Thread @worker-thread 1000)
        (let [events (map #(json/read-str % :key-fn keyword) (str/split-lines (slurp file)))]
          (is (= "summary" (:event (last events))))
          (is (not-any? #(= "late" (:event %)) events))))
      (finally
        (.countDown release)
        (stop-launched! run)
        (.delete file)))))

(deftest weighted-tasks-execute-only-the-selected-operation
  (let [calls (atom [])
        run (bench/compile-benchmark
             {:seed 123
              :tasks (vec (repeat 100 {:t :pick-weighted
                                      :choices [[0 (call (fn [_] (throw (Exception. "zero weight"))))]
                                                [1 (call (fn [_] (swap! calls conj :a)))]
                                                [2 (call (fn [_] (swap! calls conj :b)))]]}))})]
    (run)
    (let [first-run @calls]
      (is (= 100 (count first-run)))
      (is (= #{:a :b} (set first-run)))
      (reset! calls [])
      (run)
      (is (= first-run @calls)))))
