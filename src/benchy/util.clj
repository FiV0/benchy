(ns benchy.util
  "Small, dependency-free helpers reimplemented from xtdb.util for the benchmark
   harness."
  (:refer-clojure :exclude [with-open])
  (:require [clojure.tools.logging :as log])
  (:import (java.io File)
           (java.nio.file Files FileVisitOption LinkOption Path Paths)
           (java.nio.file.attribute FileAttribute)
           (java.util Comparator)
           (java.util.concurrent ThreadFactory)
           (java.util.concurrent.atomic AtomicLong)))

(defn ->path
  "Coerce a string/File/Path into a java.nio.file.Path."
  ^Path [x]
  (cond
    (instance? Path x) x
    (instance? File x) (.toPath ^File x)
    (string? x) (Paths/get ^String x (make-array String 0))
    :else (throw (IllegalArgumentException. (str "Cannot coerce to Path: " (pr-str x))))))

(defn ->prefix-thread-factory
  "A ThreadFactory that names threads `<prefix>-<n>`."
  ^ThreadFactory [^String prefix]
  (let [counter (AtomicLong. 0)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r)
          (.setName (str prefix "-" (.getAndIncrement counter))))))))

(defmacro with-open
  "Like clojure.core/with-open, but skips bindings that evaluate to nil
   (clojure.core/with-open would NPE)."
  [bindings & body]
  (assert (vector? bindings) "a vector for its binding")
  (assert (even? (count bindings)) "an even number of forms in binding vector")
  (if (zero? (count bindings))
    `(do ~@body)
    `(let [c# ~(bindings 1)
           ~(bindings 0) c#]
       (try
         (with-open ~(subvec bindings 2) ~@body)
         (finally
           (when (some? c#)
             (.close ^java.lang.AutoCloseable c#)))))))

(defn delete-dir
  "Recursively delete a directory (no-op if it doesn't exist)."
  [dir]
  (let [path (->path dir)]
    (when (Files/exists path (make-array LinkOption 0))
      (with-open [stream (Files/walk path (make-array FileVisitOption 0))]
        (doseq [^Path p (vec (.toArray (.sorted stream (Comparator/reverseOrder))))]
          (Files/deleteIfExists p))))))

(defn create-tmp-dir
  "Create a fresh temporary directory, returning its Path."
  ^Path [prefix]
  (Files/createTempDirectory (str prefix) (make-array FileAttribute 0)))

(defmacro with-tmp-dirs
  "Binds each symbol in `syms` to a fresh temp dir Path, deleting them after `body`.
   `syms` is a collection literal of symbols, e.g. `#{node-dir}` or `[a b]`."
  [syms & body]
  (let [syms (vec syms)]
    `(let [~@(mapcat (fn [s] [s `(create-tmp-dir ~(name s))]) syms)]
       (try
         ~@body
         (finally
           ~@(map (fn [s] `(delete-dir ~s)) syms))))))

(defn install-uncaught-exception-handler!
  "Log (rather than silently swallow) uncaught exceptions on any thread."
  []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread throwable]
       (log/error throwable "Uncaught exception on thread" (.getName ^Thread thread))))))
