{:dev {:dependencies [[ch.qos.logback/logback-classic "1.0.9"]]
       :plugins [[lein-pallet-release "RELEASE"]]
       :pallet-release
       {:url "https://pbors:${GH_TOKEN}@github.com/pallet/ssh-transport.git",
        :branch "master"}}
 :no-checkouts {:checkout-deps-shares ^{:replace true} []},
 :doc {:dependencies [[com.palletops/pallet-codox "0.1.0-SNAPSHOT"]]
       :plugins [[codox/codox.leiningen "0.6.4"]
                 [lein-marginalia "0.7.1"]]
       :codox {:writer codox-md.writer/write-docs
               :output-dir "doc/0.4/api"
               :src-dir-uri "https://github.com/pallet/ssh-transport/blob/develop"
               :src-linenum-anchor-prefix "L"}
       :aliases {"marg" ["marg" "-d" "doc/0.4/annotated"]
                 "codox" ["doc"]
                 "doc" ["do" "codox," "marg"]}}
 :release
 {:set-version
  {:updates [{:path "README.md" :no-snapshot true}]}}}
