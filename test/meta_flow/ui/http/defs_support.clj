(ns meta-flow.ui.http.defs-support
  (:require [cheshire.core :as json]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.ui.http :as ui.http]))

(defn temp-overlay-root
  []
  (.getPath
   (.toFile
    (java.nio.file.Files/createTempDirectory "meta-flow-http-defs"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn json-body
  [response]
  (json/parse-string (:body response) true))

(defn start-test-server!
  []
  (let [overlay-root (temp-overlay-root)
        defs-repo (defs.loader/filesystem-definition-repository
                   {:resource-base "meta_flow/defs"
                    :overlay-root overlay-root})
        server (ui.http/start-server! {:defs-repo defs-repo
                                       :port 0})]
    {:overlay-root overlay-root
     :defs-repo defs-repo
     :server server}))

(defn stop-test-server!
  [{:keys [server]}]
  (ui.http/stop-server! server))
