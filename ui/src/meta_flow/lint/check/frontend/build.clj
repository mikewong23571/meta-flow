(ns meta-flow.lint.check.frontend.build
  (:require [clojure.java.io :as io]
            [meta-flow.lint.check.shared :as shared]))

(def package-json-path
  "package.json")

(def node-modules-path
  "node_modules")

(def shadow-cljs-package-path
  "node_modules/shadow-cljs/package.json")

(defn- bootstrap-action
  [message]
  (str message " Then run `bb install` and rerun `bb governance`."))

(defn- path-exists?
  [path]
  (.exists (io/file path)))

(defn- npm-available?
  []
  (try
    (zero? (:exit (shared/run-command! ["npm" "--version"])))
    (catch Throwable _
      false)))

(defn build-bootstrap-status-from
  [{:keys [package-json?
           npm-available?
           node-modules?
           shadow-cljs-package?]}]
  (cond
    (not package-json?)
    {:state :missing-package-json
     :headline "frontend bootstrap is incomplete because package.json is missing"
     :action (bootstrap-action "Restore `package.json`.")}

    (not npm-available?)
    {:state :missing-npm
     :headline "frontend bootstrap is incomplete because npm is not available in PATH"
     :action "Install Node.js/npm, verify `npm --version`, then run `bb install` and rerun `bb governance`."}

    (not node-modules?)
    {:state :missing-node-modules
     :headline "frontend bootstrap is incomplete because npm dependencies are not installed"
     :action "Run `bb install` and rerun `bb governance`."}

    (not shadow-cljs-package?)
    {:state :missing-shadow-cljs-package
     :headline "frontend bootstrap is incomplete because shadow-cljs is missing from node_modules"
     :action (bootstrap-action "Restore the local dependency install.")}

    :else
    {:state :ready}))

(defn build-bootstrap-status
  []
  (build-bootstrap-status-from
   {:package-json? (path-exists? package-json-path)
    :npm-available? (npm-available?)
    :node-modules? (path-exists? node-modules-path)
    :shadow-cljs-package? (path-exists? shadow-cljs-package-path)}))

(defn frontend-build-gate
  []
  (try
    (let [{:keys [state headline action]} (build-bootstrap-status)]
      (if (not= :ready state)
        {:gate :frontend-build
         :label "frontend-build"
         :status :error
         :headline headline
         :action action}
        (let [{:keys [exit combined]} (shared/run-command! ["npm" "run" "compile:check"])
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
