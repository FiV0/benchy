(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'org.clojars.fiv0/benchy)
(def version "0.1.0")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean
  "Cleans the target path."
  [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/delete {:path class-dir})
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
  (b/write-pom {:class-dir class-dir :lib lib :version version :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/FiV0/benchy"
                      :connection "scm:git:git://github.com/FiV0/benchy.git"
                      :developerConnection "scm:git:ssh://git@github.com/FiV0/benchy.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Composable Clojure benchmark framework"]
                           [:url "https://github.com/FiV0/benchy"]
                           [:licenses [:license [:name "Mozilla Public License 2.0"]
                                                [:url "https://www.mozilla.org/MPL/2.0/"]]]]})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  {:jar-file jar-file})

(defn install
  "Install the jar into the local ~/.m2 repository."
  [_]
  (jar nil)
  (b/install {:basis @basis :lib lib :version version
              :jar-file jar-file :class-dir class-dir}))

(defn deploy
  "Build the jar and deploy it to Clojars.
  Requires CLOJARS_USERNAME and CLOJARS_PASSWORD (a deploy token) env vars."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})
              :sign-releases? false}))

(comment
  (clean nil)
  (jar nil))
