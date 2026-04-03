(ns meta-flow.runtime.registry
  (:require [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.mock :as mock]))

(defn runtime-adapter
  [adapter-id]
  (case adapter-id
    :runtime.adapter/codex (codex/codex-runtime)
    :runtime.adapter/mock (mock/mock-runtime)
    (throw (ex-info (str "Unsupported runtime adapter " adapter-id)
                    {:adapter-id adapter-id}))))
