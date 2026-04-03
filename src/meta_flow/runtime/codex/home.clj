(ns meta-flow.runtime.codex.home
  (:require [clojure.java.io :as io]
            [meta-flow.runtime.codex.fs :as fs]))

(def ^:private template-files
  [{:resource-path "meta_flow/codex_home/README.md"
    :relative-path "README.md"}
   {:resource-path "meta_flow/codex_home/config.edn"
    :relative-path "config.edn"}])

(defn codex-home-root
  [runtime-profile]
  (fs/absolute-path (:runtime-profile/codex-home-root runtime-profile)))

(defn- slurp-resource!
  [resource-path]
  (if-let [resource (io/resource resource-path)]
    (slurp resource)
    (throw (ex-info (str "Missing CODEX_HOME template resource " resource-path)
                    {:resource-path resource-path}))))

(defn install-home!
  [runtime-profile]
  (let [root (codex-home-root runtime-profile)
        _ (fs/ensure-directory! root)]
    (reduce (fn [result {:keys [resource-path relative-path]}]
              (let [target-path (str root "/" relative-path)
                    target-file (io/file target-path)]
                (if (.exists target-file)
                  (update result :codex-home/skipped-paths conj target-path)
                  (do
                    (fs/write-text-file! target-path (slurp-resource! resource-path))
                    (update result :codex-home/installed-paths conj target-path)))))
            {:codex-home/root root
             :codex-home/installed-paths []
             :codex-home/skipped-paths []}
            template-files)))
