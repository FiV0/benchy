(ns ^:no-doc benchy.runtime
  (:import (java.time Duration)
           (java.util Random)
           (java.util.concurrent Callable ExecutionException
                                 ExecutorCompletionService ExecutorService Executors Future
                                 ThreadFactory TimeUnit TimeoutException)
           (java.util.concurrent.atomic AtomicLong)))

(def default-join-wait (Duration/ofSeconds 5))

(defn check-interrupted! [worker]
  (when (or @(:benchy/cancelled? worker) (.isInterrupted (Thread/currentThread)))
    (throw (InterruptedException. "Benchmark cancelled"))))

(defn thread-factory [prefix]
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. runnable (str prefix "-" (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn stop! [^ExecutorService executor ^Duration join-wait]
  (let [interrupted? (Thread/interrupted)]
    (try
      (.shutdownNow executor)
      (.awaitTermination executor (.toNanos join-wait) TimeUnit/NANOSECONDS)
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        false)
      (finally
        (when interrupted? (.interrupt (Thread/currentThread)))))))

(defn- unwrap-get [^Future future]
  (try
    (.get future)
    (catch ExecutionException e (throw (.getCause e)))))

(defn repeat-task! [worker ^Duration duration ^Duration delay f]
  (let [start (System/nanoTime)
        duration-ns (.toNanos duration)
        delay-ns (.toNanos delay)]
    (loop []
      (check-interrupted! worker)
      (when (< (- (System/nanoTime) start) duration-ns)
        (f worker)
        (let [remaining (- duration-ns (- (System/nanoTime) start))]
          (when (pos? remaining)
            (when (pos? delay-ns)
              (.sleep TimeUnit/NANOSECONDS (min delay-ns remaining)))
            (recur)))))))

(defn parallel! [worker task functions]
  (check-interrupted! worker)
  (when (seq functions)
    (let [join-wait (or (:join-wait task) default-join-wait)
          ^Duration duration (:duration task)
          executor (Executors/newFixedThreadPool (count functions)
                                                 (thread-factory (str "benchy-" (:bench-id worker) "-worker")))
          completions (ExecutorCompletionService. executor)
          start (System/nanoTime)
          budget (long (min Long/MAX_VALUE (+' (.toNanos duration) (.toNanos ^Duration join-wait))))]
      (try
        (doseq [f functions]
          (let [child (assoc worker :random (Random. (.nextLong ^Random (:random worker))))
                run (bound-fn []
                      (check-interrupted! child)
                      (f (assoc child :thread-name (.getName (Thread/currentThread)))))]
            (.submit completions ^Callable run)))
        (.shutdown executor)
        (loop [remaining (count functions)]
          (when (pos? remaining)
            (check-interrupted! worker)
            (if-let [completed (.poll completions (max 0 (- budget (- (System/nanoTime) start))) TimeUnit/NANOSECONDS)]
              (do (unwrap-get completed) (recur (dec remaining)))
              (throw (ex-info "Tasks exceeded their duration and join-wait"
                              {:benchy/type :task-timeout :task (dissoc task :f)
                               :duration duration :join-wait join-wait})))))
        (finally
          (stop! executor join-wait))))))

(defn execute! [worker benchmark f]
  (let [executor (Executors/newSingleThreadExecutor
                 (thread-factory (str "benchy-" (:bench-id worker) "-run")))
        run (bound-fn [] (f (assoc worker :thread-name (.getName (Thread/currentThread)))))
        ^Duration timeout (:timeout benchmark)
        join-wait (or (:join-wait benchmark) default-join-wait)]
    (try
      (let [future (.submit executor ^Callable run)]
        (try
          (if (and timeout (not (.isZero timeout)))
            (.get future (.toNanos timeout) TimeUnit/NANOSECONDS)
            (.get future))
          (catch ExecutionException e (throw (.getCause e)))
          (catch TimeoutException e
            (throw (ex-info "Benchmark exceeded timeout"
                            {:benchy/type :timeout :timeout timeout} e)))))
      (finally
        (reset! (:benchy/cancelled? worker) true)
        (stop! executor join-wait)))))
