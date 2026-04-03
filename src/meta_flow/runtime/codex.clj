(ns meta-flow.runtime.codex
  (:require [meta-flow.runtime.codex.execution :as execution]
            [meta-flow.runtime.codex.process :as process]
            [meta-flow.runtime.protocol :as runtime.protocol]))

(defn ensure-launch-supported!
  [runtime-profile]
  (process/ensure-launch-supported! runtime-profile))

(defrecord CodexRuntimeAdapter []
  runtime.protocol/RuntimeAdapter
  (adapter-id [_]
    :runtime.adapter/codex)
  (prepare-run! [_ ctx task run]
    (execution/prepare-run! ctx task run))
  (dispatch-run! [_ ctx task run]
    (execution/dispatch-run! ctx task run))
  (poll-run! [_ ctx run now]
    (execution/poll-run! ctx run now))
  (cancel-run! [_ _ run reason]
    (execution/cancel-run! run reason)))

(defn codex-runtime
  []
  (->CodexRuntimeAdapter))
