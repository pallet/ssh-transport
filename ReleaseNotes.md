## 0.6.0

- Add jump-host support
  This changes the arguments for open to take a target sequence of hosts to
  connect though.  Each element in the sequence is a map with :endpoint and
  :credenttial keys.

  Also changes to always use an in-memory agent, initialised with the keys
  in the system agent.

- Add :ssh-agent-options to open options
  The :ssh-agent-options can be used to pass options to the ssh-agent used
  for the connection.  Removes all logic around agent construction.

## 0.5.1

- Add debug and trace level logging

- Fix timeout in port-reachable?

# 0.5.0

- Break out components of the command map
  This allows more freedom for the implementation to process the command
  map.  Require for proper environment forwarding.

# 0.4.5

- Update to clj-ssh 0.5.6

# 0.4.4

- Allow agent forwarding on exec

# 0.4.3

- Obfuscate logging of passwords

# 0.4.2

- Update to clj-ssh 0.5.5

- Add trace logging for open

# 0.4.1

- Update to clj-ssh 0.5.4
  Includes fixed handling of literal key strings.

- Add wait-for-port-reachable options
  Allow specification of :port-retries and :port-backoff options.

- Use local ssh-agent when :temp-key is true
  To avoid temporary keys being added to the system ssh-agent, use a local
  agent when :temp-key is true.

- Make standoff increase in connection attempts
  In order to reduce the number of connection attempts back-off connection
  attempts by a constant factor (fixed at 1.5 for now).

  Also improves logging of connection attempts.

- Remove connection function overload for state

# 0.4.0

- Remove reflection warnings

- Update pallet-common and clj-ssh

# 0.3.2

- Update to clj-ssh 0.5.2

# 0.3.1

- Support literal key strings in user

# 0.3.0

- Remove the :error key map on non-zero exit
  The generation of an error map should be the caller's responsibility.

- Reduce script output polling interval

- Update dependency versions
  Depend on clojure 1.4.0, drop slingshot, and use clj-ssh 0.5.0.

# 0.2.2

- Don't try adding nil key to ssh-agent

# 0.2.1

- Add retries on failed connect

# 0.2.0

- Update to clj-ssh 0.4.3

- Add endpoints to failed script logging

# 0.1.1

- Add :mode option to send-text and send-stream

- Add wait for port reachability

# 0.1.0

Initial release
