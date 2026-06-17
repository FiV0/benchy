(ns benchy.example
  "A small, self-contained example benchmark that hammers an in-memory map.

   It demonstrates the benchy machinery end-to-end without any external system:
     - `cli-flags`  adds a `--duration` flag,
     - `->benchmark` returns a benchmark whose `:->sut` is an atom,
     - the task tree uses a setup `:stage`, `:concurrently`, `:freq-job` and two
       instrumented `:transaction`s.

   Run it with:  clj -M -m benchy.bench example --duration PT5S"
  (:require [benchy.bench :as bench])
  (:import (java.time Duration)))

(defmethod bench/cli-flags :example [_]
  [[nil "--duration DURATION" "How long to run the workload (ISO-8601)."
    :id :duration
    :default (Duration/parse "PT5S")
    :parse-fn #(Duration/parse %)]])

(defn- insert! [worker]
  (let [a (:node worker)]
    (swap! a assoc (bench/random-str worker) {:foo (bench/random-str worker)})))

(defn- query [worker]
  (count @(:node worker)))

(defmethod bench/->benchmark :example [_ {:keys [seed duration]
                                          :or {seed 0, duration (Duration/parse "PT5S")}}]
  {:title "In-memory example benchmark"
   :benchmark-type :example
   :seed seed
   :parameters {:duration duration}
   ;; the system-under-test is just an atom holding a map
   :->sut (fn [_opts] {:node (atom {})})
   :tasks
   [{:t :do
     :tasks
     [{:t :call
       :stage :load
       :setup? true
       :f (fn [worker]
            (let [a (:node worker)]
              (dotimes [i 1000]
                (swap! a assoc i {:foo (bench/random-str worker)}))))}

      {:t :concurrently
       :duration duration
       :join-wait (Duration/ofSeconds 5)
       :thread-tasks
       [{:t :freq-job
         :duration duration
         :freq (Duration/ofMillis 10)
         :job-task {:t :call
                    :transaction :insert
                    :f (bench/wrap-in-catch insert!)}}

        {:t :freq-job
         :duration duration
         :freq (Duration/ofMillis 20)
         :job-task {:t :call
                    :transaction :query
                    :f (bench/wrap-in-catch query)}}]}]}]})
