(ns pallet.ssh.transport
  "Implementation of execution over ssh"
  (:require
   [clj-ssh.ssh :as ssh]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging])
  (:use
   [slingshot.slingshot :only [throw+]]))

(defonce default-agent-atom (atom nil))

(defn default-agent
  []
  (or @default-agent-atom
      (swap! default-agent-atom
             (fn [agent]
               (if agent agent (ssh/ssh-agent {}))))))

(defn possibly-add-identity
  [agent private-key-path passphrase]
  (try
    (when-not (ssh/has-identity? agent private-key-path)
      (ssh/add-identity
       agent {:private-key-path private-key-path :passphrase passphrase}))
    (catch Exception e
      (logging/warnf "Couldn't add key: %s" (.getMessage e))
      (logging/debugf e "Couldn't add key"))))

(defn ssh-user-credentials
  "Middleware to user the session :user credentials for SSH authentication."
  [authentication]
  (let [user (:user authentication)]
    (logging/debugf "SSH user %s %s" (:username user) (:private-key-path user))
    (possibly-add-identity
     (default-agent) (:private-key-path user) (:passphrase user))))

(defn port-reachable?
  ([ip port timeout]
     (let [socket (doto (java.net.Socket.)
                    (.setReuseAddress false)
                    (.setSoLinger false 1)
                    (.setSoTimeout timeout))]
       (try
         (.connect socket (java.net.InetSocketAddress. ip port))
         true
         (catch java.io.IOException _)
         (finally
           (try (.close socket) (catch java.io.IOException _))))))
  ([ip port]
     (port-reachable? ip port 2000)))

(defn wait-for-port-reachable
  [ip port]
  (when-not (loop [retries 180]
              (if (port-reachable? ip port)
                true
                (when (pos? retries) (recur (dec retries)))))
    (throw+
     {:type :pallet/ssh-connection-failure
      :ip ip
      :port port}
     (format "SSH port not reachable : server %s, port %s" ip port))))

(defn connect-ssh-session
  [ssh-session endpoint authentication]
  (when-not (ssh/connected? ssh-session)
    (logging/debugf "SSH connecting %s" endpoint)
    (try
      (wait-for-port-reachable (:server endpoint) (:port endpoint 22))
      (ssh/connect ssh-session)
      (catch Exception e
        (throw+
         {:type :pallet/ssh-connection-failure
          :cause e
          :ip (:server endpoint)
          :port (:port endpoint 22)
          :user (-> authentication :user :username)}
         (format
          "SSH connect : server %s, port %s, user %s"
          (:server endpoint)
          (:port endpoint 22)
          (-> authentication :user :username)))))))

(defn connect-sftp-channel
  [sftp-channel endpoint authentication]
  (when-not (ssh/connected-channel? sftp-channel)
    (try
      (ssh/connect sftp-channel)
      (catch Exception e
        (throw+
         {:type :pallet/sftp-channel-failure
          :cause e}
         (format
          "SSH connect SFTP : server %s, port %s, user %s"
          (:server endpoint)
          (:port endpoint 22)
          (-> authentication :user :username)))))))

(defn connect
  ([endpoint authentication options]
     (let [ssh-session (ssh/session
                        (default-agent)
                        (:server endpoint)
                        {:username (-> authentication :user :username)
                         :strict-host-key-checking (:strict-host-key-checking
                                                    options :no)
                         :port (:port endpoint 22)
                         :password (-> authentication :user :password)})
           _ (.setDaemonThread ssh-session true)
           _ (connect-ssh-session ssh-session endpoint authentication)
           sftp-channel (ssh/ssh-sftp ssh-session)]
       (connect-sftp-channel sftp-channel endpoint authentication)
       {:ssh-session ssh-session
        :sftp-channel sftp-channel
        :endpoint endpoint
        :authentication authentication
        :options options}))
  ([state]
     (let [state (.state state)
           ssh-session (:ssh-session state)
           _ (connect-ssh-session
              ssh-session (:endpoint state) (:authentication state))
           sftp-channel (:sftp-channel state)]
       (connect-sftp-channel
        sftp-channel (:endpoint state) (:authentication state))
       state)))

(defn close
  "Close any ssh connection to the server specified in the session."
  [{:keys [ssh-session sftp-channel endpoint] :as state}]
  (logging/debugf "SSH close %s" endpoint)
  (when sftp-channel
    (logging/debugf "SSH disconnect SFTP %s" endpoint)
    (ssh/disconnect-channel sftp-channel))
  (when ssh-session
    (logging/debugf "SSH disconnect SSH %s" endpoint)
    (ssh/disconnect ssh-session))
  state)

(defn send-stream
  [{:keys [sftp-channel] :as state} source destination {:keys [mode]}]
  [(ssh/sftp sftp-channel {} :put source destination)
   (when mode
     (ssh/sftp sftp-channel {} :chmod mode destination))])

(defn send-text
  [{:keys [sftp-channel] :as state} source destination {:keys [mode]}]
  [(ssh/sftp
    sftp-channel {}
    :put (java.io.ByteArrayInputStream. (.getBytes source)) destination)
   (when mode
     (ssh/sftp sftp-channel {} :chmod mode destination))])

(defn receive
  [{:keys [sftp-channel] :as state} source destination]
  (ssh/sftp
   sftp-channel {} :get source (io/output-stream (io/file destination))))

(def
  ^{:doc "Specifies the buffer size used to read the ssh output stream.
    Defaults to 10K, to match clj-ssh.ssh/*piped-stream-buffer-size*"}
  ssh-output-buffer-size (atom (* 1024 10)))

(def
  ^{:doc "Specifies the polling period for retrieving ssh command output.
    Defaults to 1000ms."}
  output-poll-period (atom 1000))

(defn exec
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   {:keys [execv in] :as code}
   {:keys [output-f pty] :as options}]
  (logging/tracef "ssh/exec %s" code)
  (logging/tracef "ssh/exec %s" (pr-str state))
  (logging/tracef "ssh/exec session connected %s" (ssh/connected? ssh-session))
  (if output-f
    (let [{:keys [channel out-stream]} (ssh/ssh
                                        ssh-session
                                        {:cmd (string/join " " execv)
                                         :in in
                                         :pty (:pty options true)
                                         :out :stream})
          shell channel
          stream out-stream
          sb (StringBuilder.)
          buffer-size @ssh-output-buffer-size
          period @output-poll-period
          bytes (byte-array buffer-size)
          read-ouput (fn []
                       (when (pos? (.available stream))
                         (let [num-read (.read stream bytes 0 buffer-size)
                               s (String. bytes 0 num-read "UTF-8")]
                           (output-f s)
                           (.append sb s)
                           s)))]
      (while (ssh/connected-channel? shell)
        (Thread/sleep period)
        (read-ouput))
      (while (read-ouput))
      (.close stream)
      (let [exit (.getExitStatus shell)
            stdout (str sb)]
        (if (zero? exit)
          {:out stdout :exit exit}
          (do
            (logging/errorf "%s Exit status  : %s" (:server endpoint) exit)
            {:out stdout :exit exit
             :error {:message (format
                               "Error executing script :\n :cmd %s\n :out %s\n"
                               code stdout)
                     :type :pallet-script-excution-error
                     :script-exit exit
                     :script-out stdout
                     :server (:server endpoint)}}))))
    (let [{:keys [out exit] :as result} (ssh/ssh
                                         ssh-session
                                         (merge
                                          (when-let [execv (seq execv)]
                                            {:cmd (apply
                                                   str
                                                   (interpose
                                                    " " (map str execv)))})
                                          {:in in :pty (:pty options true)}))]

      (if (zero? exit)
        result
        (do
          (logging/errorf "Exit status  : %s" exit)
          {:out out :exit exit
           :error {:message (format
                             "Error executing script :\n :cmd %s\n :out %s\n"
                             code out)
                   :type :pallet-script-excution-error
                   :script-exit exit
                   :script-out out
                   :server (:server endpoint)}})))))

(defn forward-to-local
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   remote-port
   local-port]
  (.setPortForwardingL ssh-session local-port (:server endpoint) remote-port))

(defn unforward-to-local
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   remote-port
   local-port]
  (.delPortForwardingL ssh-session local-port))

(defn connected?
  [state]
  (ssh/connected? (:ssh-session state)))

(defn open [endpoint authentication options]
  (ssh-user-credentials authentication)
  (connect endpoint authentication options))
