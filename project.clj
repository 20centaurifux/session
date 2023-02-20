(defproject session "0.1.0-SNAPSHOT"
  :description "Mange sessions."
  :url "https://github.com/20centaurifux/session"
  :license {:name "AGPLv3"
            :url "https://www.gnu.org/licenses/agpl-3.0"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.async "1.6.673"]]
  :plugins [[lein-cljfmt "0.9.2"]
            [lein-codox "0.10.8"]]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :codox { :output-path "./doc" })
