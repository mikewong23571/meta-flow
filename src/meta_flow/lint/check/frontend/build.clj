(ns meta-flow.lint.check.frontend.build
  (:require [clojure.java.io :as io]
            [meta-flow.lint.check.shared :as shared]))

(def package-json-path
  "package.json")

(def node-modules-path
  "node_modules")

(def shadow-cljs-package-path
  "node_modules/shadow-cljs/package.json")

(defn- path-exists?
  [path]
  (.exists (io/file path)))

(defn- npm-available?
  []
  (try
    (zero? (:exit (shared/run-command! ["npm" "--version"])))
    (catch Throwable _
      false)))

(defn build-bootstrap-status
  []
  (cond
    (not (path-exists? package-json-path))
    {:state :missing-package-json
     :headline "skipped because package.json is missing"
     :action "Restore package.json before enabling frontend build governance."}

    (not (path-exists? node-modules-path))
    {:state :missing-node-modules
     :headline "skipped because frontend npm dependencies are not installed"
     :action "Run `bb ui:install` to enable frontend build governance."}

    (not (path-exists? shadow-cljs-package-path))
    {:state :missing-shadow-cljs-package
     :headline "skipped because shadow-cljs npm dependencies are incomplete"
     :action "Run `bb ui:install` to restore frontend build dependencies."}

    (not (npm-available?))
    {:state :missing-npm
     :headline "skipped because npm is not available in PATH"
     :action "Install Node.js/npm or adjust PATH before enabling frontend build governance."}

    :else
    {:state :ready}))

(defn frontend-build-gate
  []
  (try
    (let [{:keys [state headline action]} (build-bootstrap-status)]
      (if (not= :ready state)
        {:gate :frontend-build
         :label "frontend-build"
         :status :skipped
         :headline headline
         :action action}
        (let [{:keys [exit combined]} (shared/run-command! ["npm" "run" "ui:check"])
              cause (when-not (zero? exit)
                      (or (shared/first-matching-line combined #"^shadow-cljs")
                          (shared/first-matching-line combined #"^Execution error")
                          (shared/first-matching-line combined #"^The ")
                          (shared/first-matching-line combined #"^npm ERR!")
                          (shared/first-matching-line combined #"^\[:app\]")
                          (shared/first-matching-line combined #".+")))]
          {:gate :frontend-build
           :label "frontend-build"
           :status (if (zero? exit) :pass :error)
           :headline (if (zero? exit)
                       "frontend build check passed"
                       "frontend build check failed")
           :cause cause
           :action "Fix CLJS compile or npm dependency issues before trusting frontend behavior."})))
    (catch Throwable throwable
      {:gate :frontend-build
       :label "frontend-build"
       :status :error
       :headline "frontend build check failed before completing"
       :cause (.getMessage throwable)
       :action "Recover the frontend build runner before trusting frontend behavior."})))
