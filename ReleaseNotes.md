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
