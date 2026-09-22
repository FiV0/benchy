(ns benchy.examples.sqlite
  (:require [benchy.core :as bench]
            [clojure.pprint :as pprint]
            [next.jdbc :as jdbc])
  (:import (java.time Duration)))

(def row-count 1000)

(defn- setup! [{:keys [context]}]
  (jdbc/with-transaction [tx (:connection context)]
    (jdbc/execute-one! tx ["CREATE TABLE counters (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)"])
    (doseq [id (range row-count)]
      (jdbc/execute-one! tx ["INSERT INTO counters (id, value) VALUES (?, 0)" id]))))

(defn- lookup! [{:keys [context] :as worker}]
  (let [id (.nextInt (bench/rng worker) row-count)]
    (when-not (jdbc/execute-one! (:connection context)
                               ["SELECT value FROM counters WHERE id = ?" id])
      (throw (ex-info "Counter missing" {:id id})))))

(defn- increment! [{:keys [context] :as worker}]
  (let [id (.nextInt (bench/rng worker) row-count)
        result (jdbc/execute-one! (:connection context)
                                 ["UPDATE counters SET value = value + 1 WHERE id = ?" id])]
    (when-not (= 1 (:next.jdbc/update-count result))
      (throw (ex-info "Counter not updated" {:id id})))))

(defn benchmark [duration-ms]
  {:title "SQLite counters"
   :seed 42
   :parameters {:rows row-count :duration-ms duration-ms}
   :tasks [{:t :call
            :stage :setup
            :setup? true
            :f setup!}
           {:t :pool
            :stage :workload
            ;; One worker uses the connection; no concurrent JDBC access.
            :thread-count 1
            :duration (Duration/ofMillis duration-ms)
            :pooled-task
            {:t :pick-weighted
             :choices [[80 {:t :call :transaction :lookup :f lookup!}]
                       [20 {:t :call :transaction :increment :f increment!}]]}}]})

(defn run
  "Run with clj -X:sqlite-example, optionally adding :duration-ms 5000."
  [{:keys [duration-ms] :or {duration-ms 2000}}]
  ;; Keep one connection open for the lifetime of this private in-memory DB.
  (with-open [connection (jdbc/get-connection "jdbc:sqlite::memory:")]
    (let [result (bench/run-benchmark (benchmark duration-ms) {:connection connection})]
      (pprint/pprint (select-keys result [:status :transaction-count :error-count
                                        :setup-time-ms :time-taken-ms :throughput :errors]))
      (pprint/print-table [:transaction :count :error-count :mean-ms :p50-ms :p99-ms]
                         (:transactions result))
      result)))
