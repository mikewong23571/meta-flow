(ns meta-flow.cli.test-support)

(defn temp-cli-system
  []
  (let [temp-dir (java.nio.file.Files/createTempDirectory "meta-flow-cli-test"
                                                          (make-array java.nio.file.attribute.FileAttribute 0))
        root (.toFile temp-dir)]
    {:db-path (str root "/meta-flow.sqlite3")
     :artifacts-dir (str root "/artifacts")
     :runs-dir (str root "/runs")
     :codex-home-dir (str root "/codex-home")}))
