# Benchy

Benchy is a small library that in Clojure spirit tries to write benchmarks as data on functions.
The idea is to create larger benchmarks from a set of small primitives. It tries to provide
orchestration, tracing, timing and structured reports for benchmarks.

The library was originally extracted from [XTDB](https://github.com/xtdb/xtdb/) where I first encountered it.

## Example
```clojure
(def batch-count 50)
(def batch-size 200)
(def categories ["books" "games" "music" "tools"])

(defn create-schema! [{{:keys [ds]} :context}]
  (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
  (jdbc/execute! ds ["DROP TABLE IF EXISTS orders"])
  (jdbc/execute! ds ["CREATE TABLE orders (id INTEGER PRIMARY KEY, category TEXT NOT NULL, amount INTEGER NOT NULL)"])
  (jdbc/execute! ds ["CREATE INDEX orders_category ON orders (category)"]))

(defn insert-batch! [{:keys [context state random]}]
  (let [start (swap! (:rows state) + batch-size)
        rows  (for [id (range (- start batch-size) start)]
                [id (random/uniform-nth random categories) (random/next-int random 1000)])]
    (jdbc/with-transaction [tx (:ds context)]
      (with-open [ps (jdbc/prepare tx ["INSERT INTO orders (id, category, amount) VALUES (?, ?, ?)"])]
        (jdbc/execute-batch! ps rows)))))

(defn lookup-by-id [{:keys [context state random]}]
  (jdbc/execute-one! (:ds context) ["SELECT * FROM orders WHERE id = ?"
                                    (random/next-long random @(:rows state))]))

(defn category-total [{:keys [context random]}]
  (jdbc/execute-one! (:ds context) ["SELECT count(*), sum(amount) FROM orders WHERE category = ?"
                                    (random/uniform-nth random categories)]))

(def benchmark
  {:title "SQLite example"
   :seed 42
   :parameters {:batch-count batch-count :batch-size batch-size}
   :->state (fn [] {:rows (atom 0)})
   :tasks [{:t :call :stage :schema :setup? true :f create-schema!}

           ;; Ingestion: a fixed number of batches, each one timed as a transaction.
           {:t :do
            :stage :ingest
            :tasks (repeat batch-count {:t :call :transaction :insert-batch :f insert-batch!})}

           ;; Queries: four readers for two seconds, mixing point lookups and aggregates.
           {:t :pool
            :stage :query
            :thread-count 4
            :duration (Duration/ofSeconds 2)
            :pooled-task {:t :pick-weighted
                          :choices [[0.8 {:t :call :transaction :lookup-by-id :f lookup-by-id}]
                                    [0.2 {:t :call :transaction :category-total :f category-total}]]}}]})
```

## Worker map

## Tasks

## Development

```sh
clj -X:test
clj -T:build jar
```

The build creates a regular library JAR and Maven metadata under `target/`; dependencies are not bundled.
No CLI or concrete benchmark suite is included.

### Licence

Benchy is licensed under Mozilla Public License 2.0.

See [LICENSE](LICENSE) for the full licence.
