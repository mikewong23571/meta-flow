(ns meta-flow.runtime.mock
  (:require [meta-flow.runtime.mock.execution :as execution]
            [meta-flow.runtime.protocol :as runtime.protocol]))

(defrecord MockRuntimeAdapter []
  runtime.protocol/RuntimeAdapter
  (adapter-id [_]
    :runtime.adapter/mock)
  (prepare-run! [_ _ task run]
    (execution/prepare-run! task run))
  (dispatch-run! [_ ctx task run]
    (execution/dispatch-run! ctx task run))
  (poll-run! [_ ctx run now]
    (execution/poll-run! ctx run now))
  (cancel-run! [_ _ run reason]
    (execution/cancel-run! run reason)))

(defn mock-runtime
  []
  (->MockRuntimeAdapter))
