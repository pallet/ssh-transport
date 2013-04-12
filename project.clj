(defproject com.palletops/ssh-transport "0.4.2-SNAPSHOT"
  :description "Functions for executing scripts over ssh."
  :url "http://palletops.com"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.logging "0.2.0"]
                 [clj-ssh "0.5.5"]
                 [com.palletops/pallet-common "0.4.0"]])
