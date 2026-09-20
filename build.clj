(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.FiV0/benchy)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
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
  (b/copy-file {:src "UPSTREAM.md" :target (str class-dir "/META-INF/UPSTREAM.md")})
  (b/write-pom {:class-dir class-dir :lib lib :version version :basis basis
                :src-dirs ["src"]
                :pom-data [[:description "Composable Clojure benchmark framework"]
                           [:licenses [:license [:name "Mozilla Public License 2.0"]
                                                [:url "https://www.mozilla.org/MPL/2.0/"]]]]})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  {:jar-file jar-file})

(comment
  (clean nil)
  (jar nil))
