(ns pallet.ssh.transport
  "Implementation of execution over ssh"
  (:require
   [clj-ssh.ssh :as ssh]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [schema.core :as schema :refer [either enum maybe optional-key validate]])
  (:import
   [java.io InputStream IOException]))

(def Credentials
  {:username String
   (optional-key :private-key) String
   (optional-key :public-key) String
   (optional-key :private-key-path) String
   (optional-key :public-key-path) String
   (optional-key :passphrase) String
   (optional-key :password) String})

(def Endpoint
  {:server String
   (optional-key :port) schema/Int})

(def Target
  {:endpoint Endpoint
   :credentials Credentials})

(def OpenOptions
  {(optional-key :max-tries) schema/Int
   (optional-key :backoff) schema/Int
   ;; (optional-key :port-retries) schema/Int
   (optional-key :ssh-agent-options) {schema/Keyword schema/Any}
   (optional-key :strict-host-key-checking) (enum :no :yes)})

(def State
  {:ssh-session (schema/pred ssh/session? 'session?)
   :sftp-channel schema/Any
   :target [Target]
   :options OpenOptions})

(defn obfuscate-credentials
  [credentials]
  (if (:password credentials)
    (assoc credentials :password "********")
    credentials))

(defn- ssh-agent
  [options]
  (if (:use-system-ssh-agent options true)
    (let [system-agent (ssh/ssh-agent options)
          agent (ssh/ssh-agent (assoc options :use-system-ssh-agent nil))]
      (ssh/copy-identities system-agent agent)
      agent)
    (ssh/ssh-agent options)))

(defn- possibly-add-identity
  "Try adding the given identity, logging issues, but not raising an error."
  [agent {:keys [private-key private-key-path passphrase] :as credentials}]
  {:pre [(validate Credentials credentials)]}
  (try
    (when-not (ssh/has-identity? agent private-key-path)
      (ssh/add-identity agent credentials))
    (catch Exception e
      (logging/warnf "Couldn't add key: %s" (.getMessage e))
      (logging/debugf e "Couldn't add key"))))

(defn- ssh-user-credentials
  ":user credentials for SSH authentication."
  [agent {:keys [username private-key-path private-key password]
          :as credentials}]
  (logging/debugf "SSH %s" (obfuscate-credentials credentials))
  (when (or private-key-path private-key)
    (possibly-add-identity agent credentials)))

(defn- connect-ssh-session
  [ssh-session {:keys [endpoint credentials]}
   {:keys [timeout]
    :or {timeout 10000}
    :as options}]
  {:pre [(validate Endpoint endpoint)
         (validate Credentials credentials)]}
  (when-not (ssh/connected? ssh-session)
    (logging/debugf "SSH connecting %s" endpoint)
    (try
      (ssh/connect ssh-session timeout)
      (catch Exception e
        (throw
         (ex-info
          (format
           "SSH connect: server %s port %s user %s password %s pk-path %s pk %s"
           (:server endpoint)
           (:port endpoint 22)
           (:username credentials)
           (when-let [p (:password credentials)]
             (string/replace p #"." "*"))
           (:private-key-path credentials)
           (:private-key credentials))
          {:type :pallet/ssh-connection-failure
           :ip (:server endpoint)
           :port (:port endpoint 22)
           :user (:username credentials)}
          e))))))

(defn- connect-sftp-channel
  [sftp-channel {:keys [endpoint credentials] :as target}]
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
           (:username credentials))
          {:type :pallet/sftp-channel-failure}
          e))))))

(defn strict-host-key-checking [options]
  (if (#{true :yes} (:strict-host-key-checking options))
    :yes :no))

(defn- attempt-connect
  [agent target options]
  (logging/debugf
   "attempt-connect target"
   (mapv #(update-in % [:credentials] obfuscate-credentials) target))
  (let [ssh-session
        (if (> (count target) 1)
          (ssh/jump-session
           agent
           (map
            (fn [{:keys [endpoint credentials]}]
              {:hostname (:server endpoint)
               :port (:port endpoint 22)
               :username (:username credentials)
               :password (:password credentials)
               :strict-host-key-checking (strict-host-key-checking options)})
            target)
           (select-keys options [:timeout]))
          (let [{:keys [endpoint credentials]} (first target)]
            (doto
                (ssh/session
                 agent
                 (:server endpoint)
                 {:username (:username credentials)
                  :strict-host-key-checking (strict-host-key-checking options)
                  :port (:port endpoint 22)
                  :password (:password credentials)})
              (.setDaemonThread true))))
        _ (connect-ssh-session ssh-session (last target) options)
        sftp-channel (ssh/ssh-sftp (ssh/the-session ssh-session))]
    (connect-sftp-channel sftp-channel (last target))
    (ssh/session? ssh-session)
    {:ssh-session ssh-session
     :sftp-channel sftp-channel
     :target target
     :options options}))

(defn connect
  "Connect to the ssh endpoint, optionally specifying the maximum number
   of connection attempts, and the backoff between each attempt."
  [agent target {:keys [max-tries backoff] :or {backoff 2000} :as options}]
  {:post [(validate State %)]}
  (loop [max-tries (or max-tries 1)
         backoff backoff
         e nil]
    (if (pos? max-tries)
      (let [[s e] (try
                    [(attempt-connect agent target options)]
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
     (logging/debugf "send-text set mode %s %s" destination mode)
     (ssh/sftp sftp-channel {} :chmod mode destination))])

(defn send-text
  [{:keys [sftp-channel] :as state} ^String source destination {:keys [mode]}]
  [(ssh/sftp
    sftp-channel {}
    :put (java.io.ByteArrayInputStream. (.getBytes source)) destination)
   (when mode
     (logging/debugf "send-text set mode %s %s" destination mode)
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
   {:keys [execv in env-cmd env env-fwd prefix] :as code}
   {:keys [agent-forwarding output-f pty] :as options}]
  (logging/tracef "ssh/exec %s" code)
  (logging/tracef "ssh/exec options %s" options)
  (logging/tracef "ssh/exec %s" (pr-str state))
  (logging/tracef "ssh/exec session connected %s" (ssh/connected? ssh-session))
  (let [execv (seq (concat prefix
                           (when (or env env-fwd)
                             (concat
                              [env-cmd]
                              (map
                               (fn [k]
                                 (str (name k) "=\"${" (name k) "}\""))
                               env-fwd)
                              (when env
                                (reduce-kv
                                 (fn [r k v] (conj r (str (name k) "=" v)))
                                 []
                                 env))))
                           execv))]
    (logging/tracef "ssh/exec command %s" (string/join " " execv))
    (if output-f
      (let [{:keys [channel ^InputStream out-stream]}
            (ssh/ssh
             (ssh/the-session ssh-session)
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
                                           (ssh/the-session ssh-session)
                                           (merge
                                            (when-let [execv (seq execv)]
                                              {:cmd (apply
                                                     str
                                                     (interpose
                                                      " " (map str execv)))})
                                            {:in in :pty (:pty options true)}))]
        (when-not (zero? exit)
          (logging/warnf "Exit status  : %s" exit))
        result))))

(defn forward-to-local
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   remote-port
   local-port]
  (ssh/forward-local-port
   (ssh/the-session ssh-session) local-port (:server endpoint) remote-port))

(defn unforward-to-local
  [{:keys [ssh-session sftp-channel endpoint authentication] :as state}
   remote-port
   local-port]
  (ssh/unforward-local-port (ssh/the-session ssh-session) local-port))

(defn connected?
  "Predicate to test if a target state is open"
  [state]
  (ssh/connected? (:ssh-session state)))

(defn open
  "Open an SSH connection to `target`, a sequence of maps specifying a
  connection route.  Each map has `:endpoint` and `:credentials` keys.
  Returns a connection state."
  [target
   {:keys [backoff max-tries ssh-agent-options strict-host-key-checking]
    :as options}]
  {:pre [(validate [Target] target)
         (or (validate (maybe OpenOptions) options) true)]}
  (logging/debugf "open %s %s" (pr-str target) options)
  (let [agent (ssh-agent ssh-agent-options)]
    (doseq [{:keys [credentials]} target]
      (ssh-user-credentials agent credentials))
    (connect agent target options)))
