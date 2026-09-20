(ns benchy.helpers-test
  (:require [benchy.core :as bench]
            [benchy.random :as random]
            [benchy.stats :as stats]
            [clojure.test :refer [deftest is]])
  (:import (java.time Instant LocalTime)
           (java.util Random UUID)
           (java.util.concurrent.atomic AtomicLong)))

(deftest seeded-generators-and-shuffle
  (let [sample (fn []
                 (let [r (Random. 42)]
                   [(random/next-int r) (random/next-long r)
                    (random/next-uuid r) (random/next-string r 30)
                    (random/next-instant r 2020 2021) (random/next-local-time r)
                    (random/shuffle r (range 10))]))
        [i n ^UUID uuid s ^Instant instant time shuffled] (sample)]
    (is (= (sample) (sample)))
    (is (<= 0 i Integer/MAX_VALUE))
    (is (<= 0 n Long/MAX_VALUE))
    (is (= 4 (.version uuid)))
    (is (= 2 (.variant uuid)))
    (is (= 30 (count s)))
    (is (re-matches #"[a-zA-Z0-9]+" s))
    (is (not (.isBefore instant (Instant/parse "2020-01-01T00:00:00Z"))))
    (is (.isBefore instant (Instant/parse "2022-01-01T00:00:00Z")))
    (is (instance? LocalTime time))
    (is (= (set (range 10)) (set shuffled)))
    (is (nil? (random/uniform-nth (Random.) [])))
    (is (false? (random/chance? (Random.) 0.0)))
    (is (true? (random/chance? (Random.) 1.0)))))

(deftest weighted-samplers-respect-zero-weights
  (let [r (proxy [Random] [] (nextInt [_] 0) (nextDouble [] 0.0))]
    (is (= :positive ((bench/weighted-sample-fn [[:zero 0] [:positive 1]]) r)))
    (is (= :positive (random/weighted-nth r [0 1] [:zero :positive])))))

(deftest weighted-sampling-distribution
  (let [sampler (bench/weighted-sample-fn [[:a 1] [:b 3] [:never 0]])
        r (Random. 123)
        counts (frequencies (repeatedly 20000 #(sampler r)))]
    (is (not (contains? counts :never)))
    (is (< 0.23 (/ (:a counts) 20000.0) 0.27))
    (is (nil? ((bench/weighted-sample-fn []) r)))
    (is (= :only ((bench/weighted-sample-fn [[:only 2]]) r)))
    (doseq [weights [[[:a -1]] [[:a Double/NaN]] [[:a Double/POSITIVE_INFINITY]] [[:a 0]]]]
      (is (thrown? clojure.lang.ExceptionInfo (bench/weighted-sample-fn weights))))
    (is (= #{:a :b} (set (repeatedly 100 #(random/weighted-nth r [0 0] [:a :b])))))))

(deftest worker-samplers-and-counters
  (let [worker {:random (Random. 12)} counter (AtomicLong.)]
    (is (nil? (bench/sample-flat worker counter)))
    (is (nil? (bench/sample-gaussian worker counter)))
    (bench/set-count! counter 9)
    (is (= 10 (bench/inc-count! counter)))
    (is (every? #(<= 0 % 10) (repeatedly 100 #(bench/sample-flat worker counter))))
    (is (every? #(<= 0 % 10) (repeatedly 100 #(bench/sample-gaussian worker counter))))
    (is (nil? (bench/random-nth worker [])))
    (is (= :one (bench/random-nth worker [:one])))
    (is (string? (bench/random-str worker)))
    (is (boolean? (bench/random-bool worker)))
    (is (= 2 (count (bench/random-seq worker {:min 2 :max 3} (constantly :x)))))))

(deftest statistical-summaries
  (is (nil? (stats/distribution-stats [])))
  (is (nil? (stats/timing-stats [])))
  (is (= {:count 5 :total 15 :min 1 :max 5 :mean 3.0
          :p50 3 :p90 4 :p95 4 :p99 4}
         (stats/distribution-stats [5 2 3 1 4])))
  (is (= 1.0 (:mean-ms (stats/timing-stats [1000000]))))
  (is (= -2 (stats/round-down -1.5)))
  (is (= 1.0 (stats/draw-lognormal (Random.) 0 0)))
  (is (= 0 (stats/draw-poisson (Random.) 0)))
  (is (thrown? IllegalArgumentException (stats/draw-lognormal-poisson (Random.) 1 0)))
  (doseq [lambda [4.0 100.0]]
    (let [r (Random. 123)
          samples (repeatedly 10000 #(stats/draw-poisson r lambda))]
      (is (every? #(and (integer? %) (not (neg? %))) samples))
      (is (< (Math/abs (- lambda (:mean (stats/distribution-stats samples)))) 0.5)))))
