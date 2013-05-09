(ns pallet.ssh.transport
  "Implementation of execution over ssh"
  (:require
   [clj-ssh.ssh :as ssh]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging])
  (:import
   [java.io InputStream IOException]))

;;; Default clj-ssh agent
(defonce default-agent-atom (atom nil))

(defn default-agent
  []
  (or @default-agent-atom
      (swap! default-agent-atom
             (fn [agent]
               (or agent (ssh/ssh-agent {}))))))

(defn agent-for-authentication
  "Returns an agent to use for authentication.  Returns the system ssh-agent,
  unless the :temp-key is set in the :user map, in which case a local
  ssh-agent is returned."
  [authentication]
  (if (-> authentication :user :temp-key)
    (ssh/ssh-agent {:use-system-ssh-agent nil})
    (default-agent)))

(defn possibly-add-identity
  "Try adding the given identity, logging issues, but not raising an error."
  [agent {:keys [private-key private-key-path passphrase] :as credentials}]
  (try
    (when-not (ssh/has-identity? agent private-key-path)
      (ssh/add-identity agent credentials))
    (catch Exception e
      (logging/warnf "Couldn't add key: %s" (.getMessage e))
      (logging/debugf e "Couldn't add key"))))

(defn ssh-user-credentials
  "Middleware to user the session :user credentials for SSH authentication."
  [agent authentication]
  (let [{:keys [username private-key-path private-key password] :as user}
        (:user authentication)]
    (logging/debugf
     "SSH user %s :private-key-path %s :private-key %s :password %s"
     username private-key-path private-key
     (when password (string/replace password #"." "*")))
    (when (or (:private-key-path user) (:private-key user))
      (possibly-add-identity agent user))))

(defn port-reachable?
  "Predicate test if a we can connect to the given `port` on `ip`."
  ([^String ip port timeout]
     (logging/debugf "port-reachable? ip %s port %s timeout %s" ip port timeout)
     (let [socket (doto (java.net.Socket.)
                    (.setReuseAddress false)
                    (.setSoLinger false 1)
                    (.setSoTimeout timeout))]
       (try
         (.connect socket (java.net.InetSocketAddress. ip (int port)))
         (logging/debugf "port-reachable? connected")
         true
         (catch IOException _)
         (finally
           (try (.close socket) (catch IOException _))))))
  ([ip port]
     (port-reachable? ip port 2000)))

(defn wait-for-port-reachable
  "Wait for a port to be reachable. Retries multiple times with a default
   connection timeout on each attempt."
  [ip port {:keys [port-retries port-standoff]
            :or {port-retries 30 port-standoff 1000}}]
  (logging/debugf "wait-for-port-reachable ip %s port %s" ip port)
  (when-not (loop [retries port-retries
                   standoff port-standoff]
              (if (port-reachable? ip port)
                true
                (when (pos? retries)
                  (logging/debugf
                   "wait-for-port-reachable standoff %s" standoff)
                  (Thread/sleep standoff)
                  (recur (dec retries) (long (* standoff 1.5))))))
    (throw
     (ex-info
      (format "SSH port not reachable : server %s, port %s" ip port)
      {:type :pallet/ssh-connection-failure
       :ip ip
       :port port}))))

(defn connect-ssh-session
  [ssh-session endpoint authentication
   {:keys [port-retries port-standoff] :as options}]
  (when-not (ssh/connected? ssh-session)
    (logging/debugf "SSH connecting %s" endpoint)
    (try
      (wait-for-port-reachable (:server endpoint) (:port endpoint 22) options)
      (ssh/connect ssh-session)
      (catch Exception e
        (throw
         (ex-info
          (format
           "SSH connect: server %s port %s user %s password %s pk-path %s pk %s"
           (:server endpoint)
           (:port endpoint 22)
           (-> authentication :user :username)
           (when-let [p (-> authentication :user :password)]
             (string/replace p #"." "*"))
           (-> authentication :user :private-key-path)
           (-> authentication :user :private-key))
          {:type :pallet/ssh-connection-failure
           :ip (:server endpoint)
           :port (:port endpoint 22)
           :user (-> authentication :user :username)}
          e))))))

(defn connect-sftp-channel
  [sftp-channel endpoint authentication]
  (when-not (ssh/connected-channel? sftp-channel)
    (try
      (ssh/connect sftp-channel)
      (catch Exception e
        (logging/debugf "connect-sftp-channel failed: %s" (.getMessage e))
        (throw
         (ex-info
          (format
           "SSH connect SFTP : server %s, port %s, user %s"
           (:server endpoint)
           (:port endpoint 22)
           (-> authentication :user :username))
          {:type :pallet/sftp-channel-failure}
          e))))))

(defn attempt-connect
  [agent endpoint authentication options]
  (logging/debugf
   "attempt-connect username: %s  password: %s"
   (-> authentication :user :username)
   (when-let [pw (-> authentication :user :password)]
     (string/replace pw #"." "*")))
  (let [ssh-session (ssh/session
                     agent
                     (:server endpoint)
                     {:username (-> authentication :user :username)
                      :strict-host-key-checking (:strict-host-key-checking
                                                 options :no)
                      :port (:port endpoint 22)
                      :password (-> authentication :user :password)})
        _ (.setDaemonThread ssh-session true)
        _ (connect-ssh-session ssh-session endpoint authentication options)
        sftp-channel (ssh/ssh-sftp ssh-session)]
    (connect-sftp-channel sftp-channel endpoint authentication)
    {:ssh-session ssh-session
     :sftp-channel sftp-channel
     :endpoint endpoint
     :authentication authentication
     :options options}))

(defn connect
  "Connect to the ssh endpoint, optionally specifying the maximum number
   of connection attempts, and the backoff between each attempt."
  [agent endpoint authentication
   {:keys [max-tries backoff] :or {backoff 2000} :as options}]
  (loop [max-tries (or max-tries 1)
         backoff backoff
         e nil]
    (if (pos? max-tries)
      (let [[s e] (try
                    [(attempt-connect agent endpoint authentication options)]
                    (catch Exception e
                      (logging/debugf "connect failed: %s"
                                      (if-let [e (.getCause e)]
                                        (.getMessage e)
                                        (.getMessage e)))
                      [nil e]))]
        (if s
          s
          (do
            (logging/debugf "connect backoff: %s" backoff)
            (Thread/sleep backoff)
            (recur (dec max-tries) (long (* backoff 1.5)) e))))
      (throw e))))

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
  [{:keys [sftp-channel] :as state} ^String source destination {:keys [mode]}]
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
  output-poll-period (atom 100))

(defn exec
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   {:keys [execv in] :as code}
   {:keys [agent-forwarding output-f pty] :as options}]
  (logging/tracef "ssh/exec %s" code)
  (logging/tracef "ssh/exec %s" (pr-str state))
  (logging/tracef "ssh/exec session connected %s" (ssh/connected? ssh-session))
  (if output-f
    (let [{:keys [channel ^InputStream out-stream]}
          (ssh/ssh
           ssh-session
           {:cmd (string/join " " execv)
            :in in
            :pty (:pty options true)
            :out :stream
            :agent-forwarding agent-forwarding})
          shell channel
          stream out-stream
          sb (StringBuilder.)
          buffer-size @ssh-output-buffer-size
          period @output-poll-period
          ^bytes bytes (byte-array buffer-size)
          read-ouput (fn []
                       (when (pos? (.available stream))
                         (let [num-read (.read stream bytes 0 buffer-size)
                               s (String. bytes 0 (int num-read) "UTF-8")]
                           (output-f s)
                           (.append sb s)
                           s)))]
      (while (ssh/connected-channel? shell)
        (Thread/sleep period)
        (read-ouput))
      (while (read-ouput))
      (.close stream)
      (let [exit (ssh/exit-status shell)
            stdout (str sb)]
        (when-not (zero? exit)
          (logging/warnf "%s Exit status  : %s" (:server endpoint) exit))
        {:out stdout :exit exit}))
    (let [{:keys [out exit] :as result} (ssh/ssh
                                         ssh-session
                                         (merge
                                          (when-let [execv (seq execv)]
                                            {:cmd (apply
                                                   str
                                                   (interpose
                                                    " " (map str execv)))})
                                          {:in in :pty (:pty options true)}))]
      (when-not (zero? exit)
        (logging/warnf "Exit status  : %s" exit))
      result)))

(defn forward-to-local
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   remote-port
   local-port]
  (ssh/forward-local-port
   ssh-session local-port (:server endpoint) remote-port))

(defn unforward-to-local
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   remote-port
   local-port]
  (ssh/unforward-local-port ssh-session local-port))

(defn connected?
  [state]
  (ssh/connected? (:ssh-session state)))

(defn open [endpoint authentication options]
  (logging/trace "open %s %s %s" endpoint authentication options)
  (let [agent (agent-for-authentication authentication)]
    (ssh-user-credentials agent authentication)
    (connect agent endpoint authentication options)))
