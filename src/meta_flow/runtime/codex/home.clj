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

(defn ^:dynamic user-codex-skills-root
  []
  (str (System/getProperty "user.home") "/.codex/skills"))

(defn ^:dynamic user-codex-config-file
  []
  (str (System/getProperty "user.home") "/.codex/config.toml"))

(defn- install-skill!
  [codex-home-root skill-name]
  (let [src  (io/file (user-codex-skills-root) skill-name)
        dest (io/file codex-home-root "skills" skill-name)]
    (cond
      (.exists src)  (do (fs/copy-directory! src dest)
                         {:skill/name skill-name :skill/status :installed})
      :else          {:skill/name skill-name :skill/status :not-found})))

(defn- ensure-required-skills-present!
  [runtime-profile skill-results]
  (let [missing-skills (vec (keep #(when (= :not-found (:skill/status %))
                                     (:skill/name %))
                                  skill-results))]
    (when (seq missing-skills)
      (throw (ex-info "Codex runtime cannot start: missing required skills"
                      {:runtime-profile/id (:runtime-profile/id runtime-profile)
                       :codex-home/root (codex-home-root runtime-profile)
                       :codex-home/skills-not-found missing-skills})))
    skill-results))

(defn- install-user-config!
  [codex-home-root]
  (let [src  (io/file (user-codex-config-file))
        dest (io/file codex-home-root "config.toml")]
    (cond
      (.exists src)  (do
                       (fs/write-text-file! (.getCanonicalPath dest) (slurp src))
                       {:config/status :installed
                        :config/path (.getCanonicalPath dest)})
      :else          {:config/status :not-found})))

(defn install-home!
  [runtime-profile]
  (let [root          (codex-home-root runtime-profile)
        _             (fs/ensure-directory! root)
        file-result   (reduce (fn [result {:keys [resource-path relative-path]}]
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
                              template-files)
        file-result   (let [{:keys [config/status config/path]} (install-user-config! root)]
                        (case status
                          :installed (update file-result :codex-home/installed-paths conj path)
                          file-result))
        skill-results (->> (:runtime-profile/skills runtime-profile)
                           (mapv #(install-skill! root %))
                           (ensure-required-skills-present! runtime-profile))]
    (assoc file-result
           :codex-home/skills-installed (vec (keep #(when (= :installed  (:skill/status %)) (:skill/name %)) skill-results))
           :codex-home/skills-skipped   []
           :codex-home/skills-not-found (vec (keep #(when (= :not-found  (:skill/status %)) (:skill/name %)) skill-results)))))
