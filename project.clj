(defproject smichal/gregor "0.5.2-SNAPSHOT"
  :min-lein-version "2.0.0"
  :description "Lightweight Clojure bindings for Kafka 0.9+"
  :url "https://github.com/weftio/gregor.git"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.apache.kafka/kafka_2.11 "0.10.1.0"]]
  :plugins [[s3-wagon-private "1.1.2"]
            [lein-codox "0.9.3"]]
  :codox {:output-path "doc"}
  :deploy-repositories {"clojars" {:url "https://clojars.org/repo"
                                   :sign-releases false}})
