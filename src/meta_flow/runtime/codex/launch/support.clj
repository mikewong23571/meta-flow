(ns meta-flow.runtime.codex.launch.support
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:private smoke-enable-env
  "META_FLOW_ENABLE_CODEX_SMOKE")

(def ^:private provider-env-pattern
  #"_API_KEY(?:$|_)")

(defn env-value
  [key-name]
  (System/getenv key-name))

(defn present-env-value?
  [value]
  (boolean (seq (some-> value str/trim))))

(defn truthy-env?
  [value]
  (contains? #{"1" "true" "yes" "on"}
             (some-> value str/trim str/lower-case)))

(defn smoke-enabled?
  []
  (truthy-env? (env-value smoke-enable-env)))

(defn- configured-launch-mode
  [runtime-profile]
  (:runtime-profile/default-launch-mode runtime-profile))

(defn launch-mode
  [runtime-profile]
  (or (configured-launch-mode runtime-profile)
      (if (smoke-enabled?)
        :launch.mode/codex-exec
        :launch.mode/stub-worker)))

(defn- launch-failure-prefix
  [runtime-profile]
  (if (and (= :launch.mode/codex-exec (configured-launch-mode runtime-profile))
           (not (smoke-enabled?)))
    "Codex runtime cannot start"
    "Codex smoke test cannot start"))

(defn- prefixed-message
  [runtime-profile suffix]
  (str (launch-failure-prefix runtime-profile) ": " suffix))

(defn provider-env-keys
  [runtime-profile]
  (filter #(re-find provider-env-pattern %)
          (:runtime-profile/env-allowlist runtime-profile)))

(defn codex-command-available?
  []
  (try
    (zero? (:exit (shell/sh "codex" "--version")))
    (catch java.io.IOException _
      false)))

(defn launch-support
  [runtime-profile]
  (let [mode (launch-mode runtime-profile)
        provider-keys (vec (provider-env-keys runtime-profile))
        provider-present? (or (empty? provider-keys)
                              (some #(present-env-value? (env-value %))
                                    provider-keys))]
    (cond
      (not= :launch.mode/codex-exec mode)
      {:launch/mode mode
       :launch/ready? true}

      (not (codex-command-available?))
      {:launch/mode mode
       :launch/ready? false
       :launch/message (prefixed-message runtime-profile "`codex` command not found")}

      (not provider-present?)
      {:launch/mode mode
       :launch/ready? false
       :launch/message (prefixed-message runtime-profile
                                         "missing provider credentials for configured runtime profile")
       :launch/provider-env-keys provider-keys}

      :else
      {:launch/mode mode
       :launch/ready? true
       :launch/provider-env-keys provider-keys})))

(defn ensure-launch-supported!
  [runtime-profile]
  (let [{:keys [launch/ready? launch/message] :as support}
        (launch-support runtime-profile)]
    (when-not ready?
      (throw (ex-info message support)))
    support))
