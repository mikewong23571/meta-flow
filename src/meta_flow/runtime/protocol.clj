(ns meta-flow.runtime.protocol)

(defprotocol RuntimeAdapter
  (adapter-id [this])
  (prepare-run! [this ctx task run])
  (dispatch-run! [this ctx task run])
  (poll-run! [this ctx run now])
  (cancel-run! [this ctx run reason]))
