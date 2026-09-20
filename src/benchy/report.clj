(ns ^:no-doc benchy.report
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import (io.micrometer.core.instrument Timer)
           (io.micrometer.core.instrument.simple SimpleMeterRegistry)
           (java.lang.management ManagementFactory)
           (java.time Duration Instant)
           (java.util UUID)
           (java.util.concurrent TimeUnit)
           (oshi SystemInfo)))

(def percentiles [0.5 0.75 0.85 0.9 0.95 0.98 0.99 0.999])

(defonce ^:private output-lock (Object.))

(defn system-info []
  (let [si (SystemInfo.)
        os (.getOperatingSystem si)
        hardware (.getHardware si)
        cpu (.getProcessor hardware)]
    {:jre (System/getProperty "java.runtime.version")
     :java-opts (str/join " " (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
     :max-heap-bytes (.maxMemory (Runtime/getRuntime))
     :arch (System/getProperty "os.arch")
     :os (str os)
     :memory-bytes (.getTotal (.getMemory hardware))
     :cpu (.getName (.getProcessorIdentifier cpu))
     :physical-cores (.getPhysicalProcessorCount cpu)}))

(defn- run-context []
  (into {} (keep (fn [[env k]]
                   (when-let [v (System/getenv env)]
                     (when-not (str/blank? v) [k v]))))
        {"GIT_SHA" :git-sha, "GIT_BRANCH" :git-branch,
         "GITHUB_REPOSITORY" :github-repo, "GITHUB_RUN_ID" :github-run-id}))

(defn- json-value [value]
  (walk/postwalk #(if (or (instance? Duration %) (instance? Instant %) (instance? UUID %))
                    (str %) %) value))

(defn emit! [{:keys [writer metadata]} event]
  (when writer
    (let [line (str (json/write-str (json-value (merge metadata event))) "\n")]
      ;; Separate runs may append through different writers to the same file.
      (locking output-lock
        (.write ^java.io.Writer writer line)
        (.flush ^java.io.Writer writer)))))

(defn open-report [benchmark]
  (let [metadata (merge (run-context)
                        (select-keys benchmark [:benchmark-type :parameters :timeout :seed])
                        {:benchmark (:title benchmark)
                         :bench-id (random-uuid)
                         :started-at (Instant/now)
                         :system (system-info)})
        registry (SimpleMeterRegistry.)]
    (try
      {:lock (Object.)
       :registry registry
       :writer (when-let [file (:bench-log-file benchmark)] (io/writer file :append true))
       :metadata metadata
       :start (System/nanoTime)
       :data (atom {:transactions {} :errors [] :error-count 0
                    :setup-active 0 :setup-nanos 0 :closed? false})}
      (catch Throwable t
        (.close registry)
        (throw t)))))

(defn stage-start! [{:keys [lock data] :as report} stage setup?]
  (locking lock
    (when-not (:closed? @data)
      (let [now (System/nanoTime)]
        (when setup?
          (swap! data #(cond-> (update % :setup-active inc)
                        (zero? (:setup-active %)) (assoc :setup-start now))))
        (emit! report {:stage stage :event :stage-start :setup? (boolean setup?)})
        now))))

(defn stage-end! [{:keys [lock data] :as report} stage setup? start]
  (locking lock
    (when-not (:closed? @data)
      (let [now (System/nanoTime)]
        (when setup?
          (swap! data #(cond-> (update % :setup-active dec)
                        (= 1 (:setup-active %))
                        (update :setup-nanos + (- now (:setup-start %))))))
        (emit! report {:stage stage :event :stage-end :setup? (boolean setup?)
                      :time-taken-ms (/ (- now start) 1e6)})))))

(defn- error-detail [^Throwable error]
  (let [message (.getMessage error)]
    {:class (.getName (class error))
     :message (when message (subs message 0 (min 1024 (count message))))}))

(defn- new-timer [report transaction labels setup?]
  (let [builder (Timer/builder "benchy.operation")]
    (.tag builder "transaction" (pr-str transaction))
    (.tag builder "setup" (str (boolean setup?)))
    (doseq [[k ^String v] labels] (.tag builder (str "label." k) v))
    (.publishPercentiles builder (double-array percentiles))
    (.distributionStatisticExpiry builder (Duration/ofDays 365))
    (.maximumExpectedValue builder (Duration/ofHours 8))
    (.minimumExpectedValue builder (Duration/ofNanos 1))
    (.register builder (:registry report))))

(defn record-transaction! [{:keys [lock data] :as report} transaction labels setup? elapsed error]
  (locking lock
    (when-not (:closed? @data)
      (let [k [transaction labels (boolean setup?)]
            entry (or (get-in @data [:transactions k])
                      {:timer (new-timer report transaction labels setup?) :error-count 0})]
        (.record ^Timer (:timer entry) (long elapsed) TimeUnit/NANOSECONDS)
        (swap! data assoc-in [:transactions k] (cond-> entry error (update :error-count inc)))
        (when error
          (swap! data (fn [d]
                        (cond-> (update d :error-count inc)
                          (< (count (:errors d)) 10)
                          (update :errors conj (assoc (error-detail error)
                                                     :transaction transaction :labels labels))))))))))

(defn- transaction-result [[[transaction labels setup?] {:keys [^Timer timer error-count]}]]
  (merge {:transaction transaction :labels labels :setup? setup?
          :count (.count timer) :error-count error-count
          :success-count (- (.count timer) error-count)
          :total-time-ms (.totalTime timer TimeUnit/MILLISECONDS)
          :mean-ms (.mean timer TimeUnit/MILLISECONDS)
          :max-ms (.max timer TimeUnit/MILLISECONDS)}
         (into {} (map (fn [^io.micrometer.core.instrument.distribution.ValueAtPercentile value]
                         [(keyword (str "p" (.toPlainString (.stripTrailingZeros (BigDecimal/valueOf (* 100 (.percentile value))))) "-ms"))
                          (.value value TimeUnit/MILLISECONDS)]))
               (.percentileValues (.takeSnapshot timer)))))

(defn finish! [{:keys [lock data start] :as report} status error]
  (locking lock
    (let [now (System/nanoTime)
          {:keys [setup-active setup-start setup-nanos transactions errors error-count]} @data
          setup-nanos (+ setup-nanos (if (pos? setup-active) (- now setup-start) 0))
          total (- now start)
          measured (max 0 (- total setup-nanos))
          transactions (->> transactions (map transaction-result) (sort-by (juxt (comp str :transaction) (comp pr-str :labels) :setup?)) vec)
          successes (reduce + 0 (map :success-count (remove :setup? transactions)))
          result (merge (:metadata report)
                        {:status (if (and (= :completed status) (pos? error-count)) :completed-with-errors status)
                         :total-time-ms (/ total 1e6) :setup-time-ms (/ setup-nanos 1e6)
                         :time-taken-ms (/ measured 1e6)
                         :transaction-count (reduce + 0 (map :count transactions))
                         :error-count error-count :errors errors :transactions transactions
                         :throughput (if (pos? measured) (/ successes (/ measured 1e9)) 0.0)}
                        (when error {:failure (error-detail error)}))]
      (swap! data assoc :closed? true)
      (emit! report (assoc result :event :summary))
      result)))

(defn close! [{:keys [lock data writer registry]}]
  (locking lock
    (swap! data assoc :closed? true)
    (try
      (when writer (.close ^java.io.Writer writer))
      (finally (.close ^SimpleMeterRegistry registry)))))
