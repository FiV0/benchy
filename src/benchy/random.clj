(ns benchy.random
  (:refer-clojure :exclude [shuffle])
  (:import (com.google.common.collect MinMaxPriorityQueue)
           [java.time Instant LocalDate LocalTime ZoneOffset]
           (java.util Comparator Random UUID)))

(defn next-int
  ([^java.util.Random random]
   (next-int random Integer/MAX_VALUE))
  ([^java.util.Random random bound]
   (.nextInt random (min (max 1 bound) Integer/MAX_VALUE))))

(defn next-long
  ([^java.util.Random random]
   (next-long random Long/MAX_VALUE))
  ([^java.util.Random random bound]
   (.nextLong random (min (max 1 bound) Long/MAX_VALUE))))

(defn uniform-nth
  [^java.util.Random random coll]
  (let [c (count coll)]
    (when (pos? c)
      (nth coll (next-int random c)))))

(defn chance?
  [^java.util.Random random ^double p]
  (< (.nextDouble random) p))

(defn weighted-nth
  "Draw a single element from xs with non-negative weights.
   Returns nil if xs empty. If all weights are zero, falls back to uniform."
  [^java.util.Random rng weights xs]
  (let [ws (mapv #(double (max 0.0 %)) weights)
        xs (vec xs)
        n  (count xs)]
    (when (pos? n)
      (let [s (reduce + 0.0 ws)]
        (if (pos? s)
          (let [target (* (.nextDouble rng) s)]
            (loop [i 0 acc 0.0]
              (let [acc' (+ acc (ws i))]
                (if (or (> acc' target) (= i (dec n)))
                  (xs i)
                  (recur (inc i) acc')))))
          ;; uniform fallback
          (xs (next-int rng n)))))))

(defn weighted-sample-fn
  "Aliased random sampler:

  https://www.peterstefek.me/alias-method.html

  Given a seq of [item weight] pairs, return a function who when given a Random will return an item according to the weight distribution.

  Building the table is O(n log n); each draw is O(1). Prefer this over
  `weighted-nth` when drawing repeatedly from the same weights."
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
          ;; Scale so the average weight is 1.0: each table slot holds a mass of
          ;; 1.0, and `split` is the share of that slot given to `small`.
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

(defn rand-gaussian
  "Draw an index in [0, n) from a half-normal distribution: |N(0, 1)| / 3,
  scaled by n. Low indices are the most likely; draws past n (about 0.3%)
  are clamped to n - 1. Returns nil if n is not positive."
  [^Random random n]
  (when (pos? n)
    (let [raw (Math/abs (.nextGaussian random))
          scaled (long (* (/ raw 3.0) n))]
      (min (max scaled 0) (dec n)))))

(defn next-uuid
  "Generate a version-4 UUID using the supplied RNG, matching java.util.UUID/randomUUID idioms."
  [^java.util.Random random]
  (let [msb (.nextLong random)
        lsb (.nextLong random)
        msb (-> msb
                (bit-and (bit-not (bit-shift-left 0xF 12)))
                (bit-or  (bit-shift-left 0x4 12)))
        lsb (-> lsb
                (bit-and (bit-not (bit-shift-left 0x3 62)))
                (bit-or  (bit-shift-left 0x2 62)))]
    (UUID. msb lsb)))

(def ^:private ^String charset "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")

(defn next-string
  ^String [^java.util.Random random ^long n]
  (let [sb (StringBuilder. n)]
    (loop [i 0]
      (if (< i n)
        (do (.append sb (.charAt charset (next-int random (.length charset))))
            (recur (unchecked-inc i)))
        (.toString sb)))))

(defn next-instant
  "Return a random instant between the beginning of `start-year` (inclusive)
  and the beginning of `(inc end-year)` (exclusive).

  Defaults to the current calendar year and the preceding ten years."
  ([^java.util.Random random]
   (let [zone ZoneOffset/UTC
         end-year (.getYear (LocalDate/now zone))
         start-year (- end-year 10)]
     (next-instant random start-year end-year)))
  ([^java.util.Random random ^long start-year ^long end-year]
   (let [[^long start-year ^long end-year]
         (if (> start-year end-year)
           [end-year start-year]
           [start-year end-year])
         zone ZoneOffset/UTC
         ^Instant start (-> (LocalDate/of start-year 1 1)
                            (.atStartOfDay zone)
                            (.toInstant))
         ^Instant end   (-> (LocalDate/of (inc end-year) 1 1)
                            (.atStartOfDay zone)
                            (.toInstant))
         start-ms (.toEpochMilli start)
         end-ms (.toEpochMilli end)
         span (max 0 (- end-ms start-ms))
         offset (if (pos? span)
                  (long (Math/floor (* (.nextDouble random) (double span))))
                  0)]
     (Instant/ofEpochMilli (+ start-ms offset)))))

(defn next-local-time [^java.util.Random random]
  (LocalTime/of (next-int random 24) (next-int random 60) (next-int random 60)))

(defn shuffle
  "Shuffle a collection using the provided RNG (Fisher-Yates)."
  [^java.util.Random random coll]
  (let [arr (object-array coll)
        n (alength arr)]
    (loop [i (dec n)]
      (when (pos? i)
        (let [j (next-int random (inc i))
              tmp (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp)
          (recur (dec i)))))
    (vec arr)))
