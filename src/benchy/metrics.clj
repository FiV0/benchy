(ns benchy.metrics
  "Optional Prometheus metrics endpoint for the benchmark harness.

   The XTDB bench module relied on the XTDB node's healthz server to expose
   `/metrics` for Prometheus to scrape. Standalone, we expose it ourselves: a
   `PrometheusMeterRegistry` served over a tiny JDK HttpServer (no extra HTTP
   dependency)."
  (:require [clojure.tools.logging :as log])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (io.micrometer.prometheusmetrics PrometheusConfig PrometheusMeterRegistry)
           (java.io OutputStream)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn prometheus-registry
  "A Micrometer registry that can produce Prometheus exposition format."
  ^PrometheusMeterRegistry []
  (PrometheusMeterRegistry. PrometheusConfig/DEFAULT))

(defn start-metrics-server
  "Start an HTTP server exposing `(.scrape registry)` at `/metrics` on `port`
   (0 = an ephemeral port). Returns the HttpServer; stop it with
   `stop-metrics-server`."
  ^HttpServer [^PrometheusMeterRegistry registry ^long port]
  (let [server (HttpServer/create (InetSocketAddress. port) 0)]
    (.createContext server "/metrics"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [^HttpExchange exchange exchange
                              body (.getBytes (.scrape registry) StandardCharsets/UTF_8)]
                          (.add (.getResponseHeaders exchange)
                                "Content-Type" "text/plain; version=0.0.4; charset=utf-8")
                          (.sendResponseHeaders exchange 200 (alength body))
                          (with-open [^OutputStream os (.getResponseBody exchange)]
                            (.write os body))))))
    (.setExecutor server nil)
    (.start server)
    (log/info "Metrics server started at http://localhost:" (.getPort (.getAddress server)) "/metrics")
    server))

(defn stop-metrics-server [^HttpServer server]
  (.stop server 0))
