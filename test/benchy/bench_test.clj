(ns benchy.bench-test
  (:require [benchy.bench :as bench]
            [benchy.example]
            [benchy.metrics :as metrics]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (io.micrometer.core.instrument Counter)
           (io.micrometer.core.instrument.simple SimpleMeterRegistry)
           (java.time Clock Duration)
           (java.util Random)))

(deftest example-benchmark-runs
  (testing "the bundled example runs end-to-end and records transaction timers"
    (let [reg (SimpleMeterRegistry.)
          !sut (atom nil)
          benchmark (-> (bench/->benchmark :example {:duration (Duration/parse "PT1S")})
                        ;; inject our own registry + capture the SUT so we can assert on both
                        (assoc :->sut (fn [_opts]
                                        (let [a (atom {})]
                                          (reset! !sut a)
                                          {:node a :registry reg}))))]
      (bench/run-benchmark benchmark {})
      (is (pos? (count @@!sut)) "the in-memory map gained entries")
      (let [timer-names (->> (.getMeters reg)
                             (map #(.getName (.getId %)))
                             set)]
        (is (contains? timer-names "insert"))
        (is (contains? timer-names "query"))))))

(deftest weighted-sample-distribution
  (testing "weighted-sample-fn samples ~ proportionally to weights"
    (let [f (bench/weighted-sample-fn [[:a 1] [:b 3]])
          rng (Random. 42)
          n 20000
          freqs (frequencies (repeatedly n #(f rng)))
          b-ratio (/ (double (:b freqs)) n)]
      (is (< 0.70 b-ratio 0.80) (str "expected ~0.75, got " b-ratio))))

  (testing "degenerate cases"
    (is (nil? ((bench/weighted-sample-fn []) (Random.))))
    (is (= :only ((bench/weighted-sample-fn [[:only 1]]) (Random.))))))

(deftest compile-task-do-order
  (let [log (atom [])
        f (#'bench/compile-task {:t :do
                                 :tasks [{:t :call :f (fn [_] (swap! log conj 1))}
                                         {:t :call :f (fn [_] (swap! log conj 2))}]})]
    (f {})
    (is (= [1 2] @log))))

(deftest compile-task-call-vector-f
  (testing ":call with a [f & args] vector lifts to (apply f worker args)"
    (let [f (#'bench/compile-task {:t :call :f [(fn [w a b] [w a b]) :x :y]})]
      (is (= [:worker :x :y] (f :worker))))))

(deftest compile-task-pick-weighted
  (let [f (#'bench/compile-task {:t :pick-weighted
                                 :choices [[1 {:t :call :f (constantly :a)}]
                                           [1 {:t :call :f (constantly :b)}]]})
        worker {:random (Random. 1)}
        results (set (repeatedly 50 #(f worker)))]
    (is (every? #{:a :b} results))
    (is (= #{:a :b} results) "both choices show up over 50 picks")))

(deftest compile-task-freq-job-runs
  (let [counter (atom 0)
        f (#'bench/compile-task {:t :freq-job
                                 :duration (Duration/ofMillis 50)
                                 :freq (Duration/ofMillis 10)
                                 :job-task {:t :call :f (fn [_] (swap! counter inc))}})]
    (f {:clock (Clock/systemUTC)})
    (is (pos? @counter))))

(deftest run-with-timeout-aborts
  (is (thrown? clojure.lang.ExceptionInfo
               (#'bench/run-with-timeout (fn [] (Thread/sleep 5000) :done)
                                         (Duration/ofMillis 100))))
  (testing "nil / zero timeout just runs the fn"
    (is (= :done (#'bench/run-with-timeout (constantly :done) nil)))))

(deftest metrics-server-exposes-scrape
  (let [reg (metrics/prometheus-registry)
        counter (.register (Counter/builder "test_counter") reg)
        _ (.increment counter)
        server (metrics/start-metrics-server reg 0)
        port (.getPort (.getAddress server))]
    (try
      (let [body (slurp (str "http://localhost:" port "/metrics"))]
        (is (str/includes? body "test_counter")))
      (finally
        (metrics/stop-metrics-server server)))))
