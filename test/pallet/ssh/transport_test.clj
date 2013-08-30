(ns pallet.ssh.transport-test
  (:use
   clojure.test)
  (:require
   [clojure.java.io :as io]
   [pallet.common.filesystem :as filesystem]
   [pallet.common.logging.logutils :as logutils]
   [pallet.ssh.transport :as transport]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(defn default-private-key-path
  "Return the default private key path."
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa"))

(defn default-public-key-path
  "Return the default public key path"
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa.pub"))

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(defn test-exec
  [endpoint authorisation options]
  (let [t-state (transport/open endpoint authorisation options)]
    (testing "Default shell"
      (let [result (transport/exec t-state {:in "ls /; exit $?"} nil)]
        (is (zero? (:exit result)))
        (is (re-find #"bin" (:out result)))))
    ;; this fails for ssh for some reason
    ;; (testing "Explicit shell"
    ;;   (let [result (transport/exec
    ;;                 t-state
    ;;                 {:execv ["/bin/bash"] :in "ls /; exit $?"} nil)]
    ;;     (is (zero? (:exit result)))
    ;;     (is (re-find #"bin" (:out result)))))
    (testing "Explicit program"
      (let [result (transport/exec t-state {:execv ["/bin/ls" "/"]} nil)]
        (is (zero? (:exit result)))
        (is (re-find #"bin" (:out result)))))
    (testing "output-f"
      (let [output (atom "")
            result (transport/exec
                    t-state
                    {:execv ["/bin/ls" "/"]}
                    {:output-f (partial swap! output str)})]
        (is (zero? (:exit result)))
        (is (re-find #"bin" (:out result)))
        (is (= (:out result) @output))))
    (testing "Error return"
      (let [result (transport/exec t-state {:in "this-should-fail"} nil)]
        (is (not (zero? (:exit result))))))
    ;; Can't get this to work for some reason
    ;; (testing "Agent forwarding"
    ;;   (let [result (transport/exec
    ;;                 t-state
    ;;                 {:execv ["/usr/bin/env"]
    ;;                  :env-cmd "/usr/bin/env"
    ;;                  :env-fwd [:SSH_AUTH_SOCK]}
    ;;                 {:agent-forwarding true})]
    ;;     (is (zero? (:exit result)))
    ;;     (is (re-find
    ;;          (re-pattern (System/getenv "SSH_AUTH_SOCK"))
    ;;          (:out result)))))
    (testing "Explicit env"
      (let [tmp (java.io.File/createTempFile "pallet_" "tmp")]
        (.deleteOnExit tmp)
        (spit tmp "echo \"${XX}${SSH_CLIENT}\"")
        (try
          (let [result (transport/exec
                        t-state
                        {:prefix ["/usr/bin/sudo"]
                         :execv ["/bin/bash" (.getAbsolutePath tmp)]
                         :env-cmd "/usr/bin/env"
                         :env-fwd [:SSH_CLIENT]
                         :env {:XX "abcd"}}
                        {:agent-forwarding true})]
            (is (zero? (:exit result)))
            (is (re-find #"abcd127.0.0.1" (:out result))))
          (catch Exception e
            (.delete tmp)
            (throw e)))))))

(defn test-send
  [endpoint authorisation options]
  (let [t-state (transport/open endpoint authorisation options)]
    (testing "send"
      (filesystem/with-temp-file [tmp-src "src"]
        (filesystem/with-temp-file [tmp-dest "dest"]
          (transport/send-stream
           t-state (io/input-stream (.getPath tmp-src)) (.getPath tmp-dest) {})
          (is (= "src" (slurp tmp-dest))))))
    (testing "send-text"
      (filesystem/with-temp-file [tmp-dest "dest"]
        (transport/send-text t-state "src" (.getPath tmp-dest) {})
        (is (= "src" (slurp tmp-dest)))))
    (testing "send-text with mode"
      (filesystem/with-temp-file [tmp-dest "dest"]
        (transport/send-text t-state "src" (.getPath tmp-dest) {:mode 0666})
        (is (= "src" (slurp tmp-dest)))))
    (testing "receive"
      (filesystem/with-temp-file [tmp-src "src"]
        (filesystem/with-temp-file [tmp-dest "dest"]
          (transport/receive t-state (.getPath tmp-src) (.getPath tmp-dest))
          (is (= "src" (slurp tmp-dest))))))))

(defn test-connect-fail
  [endpoint authorisation options]
  (let [t-state (transport/open endpoint authorisation options)]
    (is false "this test is designed to fail on open")))

(use-fixtures :once (logutils/logging-threshold-fixture))

(defn default-private-key-path
  "Return the default private key path."
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa"))

(defn default-public-key-path
  "Return the default public key path"
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa.pub"))

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(deftest exec-test
  (test-exec
   {:server "localhost"}
   {:user {:private-key-path (default-private-key-path)
           :public-key-path (default-public-key-path)
           :username (test-username)}}
   nil))

(deftest send-test
  (test-send
   {:server "localhost"}
   {:user {:private-key-path (default-private-key-path)
           :public-key-path (default-public-key-path)
           :username (test-username)}}
   nil))

(deftest connection-fail-test
  (is
   (thrown-with-msg?
     clojure.lang.ExceptionInfo
     #"SSH connect: server somewhere-non-existent port 22 user.*password.*"
     (test-connect-fail
      {:server "somewhere-non-existent"}
      {:user {:private-key-path (default-private-key-path)
              :public-key-path (default-public-key-path)
              :username (test-username)}}
      {:port-retries 2})))
  (try
    (test-connect-fail
     {:server "somewhere-non-existent"}
     {:user {:private-key-path (default-private-key-path)
             :public-key-path (default-public-key-path)
             :username (test-username)}}
     {:port-retries 2})
    (catch clojure.lang.ExceptionInfo e
      (is (= "SSH port not reachable : server somewhere-non-existent, port 22"
             (.. e getCause getMessage))
          "Cause should be a port not reachable exception")))
  (testing "with retries"
    (is
     (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"SSH connect: server somewhere-non-existent port 22 user.*password.*"
       (test-connect-fail
        {:server "somewhere-non-existent"}
        {:user {:private-key-path (default-private-key-path)
                :public-key-path (default-public-key-path)
                :username (test-username)}}
        {:port-retries 2 :max-tries 3 :backoff 100})))))
