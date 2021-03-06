(defproject link-checker "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.pedestal/pedestal.service "0.1.7"]

                 ;; Remove this line and uncomment the next line to
                 ;; use Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.1.7"]
                 ;; [io.pedestal/pedestal.tomcat "0.1.7"]

                 [clj-http "0.7.2"]
                 [com.github.ragnard/hamelito "0.2.1"]
                 [de.ubercode.clostache/clostache "1.3.1"]
                 [enlive "1.1.1"]

                 ;; Logging
                 [ch.qos.logback/logback-classic "1.0.7"]
                 [org.slf4j/jul-to-slf4j "1.7.2"]
                 [org.slf4j/jcl-over-slf4j "1.7.2"]
                 [org.slf4j/log4j-over-slf4j "1.7.2"]]
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[bond "0.2.5"]]}}
  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]
  :main ^{:skip-aot true} link-checker.server)
