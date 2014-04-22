(defproject com.palletops/ssh-transport "0.5.2-SNAPSHOT"
  :description "Functions for executing scripts over ssh."
  :url "http://palletops.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [clj-ssh "0.5.9"]
                 [com.palletops/pallet-common "0.4.0"]
                 [prismatic/schema "0.2.1"]])
