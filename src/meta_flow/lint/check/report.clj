(ns meta-flow.lint.check.report
  (:require [clojure.string :as str]
            [meta-flow.governance.core :as governance]
            [meta-flow.governance.report :as report]
            [meta-flow.lint.file-length :as file-length]))

(defn issue->evidence
  [issue]
  (case (:kind issue)
    :css-file-length
    (str (:path issue) " has " (:line-count issue)
         " lines (threshold " (:threshold issue) ")")

    :css-directory-width
    (str (:path issue) " contains " (:file-count issue)
         " direct CSS files (threshold " (:threshold issue) ")")

    :directory-width
    (str (:path issue) " contains " (:file-count issue)
         " direct source files (threshold "
         (case (:level issue)
           :error file-length/directory-error-threshold
           file-length/directory-warning-threshold)
         ")")

    (str (:path issue) " has " (:line-count issue)
         " lines (threshold "
         (case (:level issue)
           :error file-length/error-threshold
           file-length/warning-threshold)
         ")")))

(defn finding->evidence
  [{:keys [filename row type message]}]
  (str filename ":" row " [" type "] " message))

(defn summary->evidence
  [summary]
  (when-let [lowest (seq (:lowest-namespaces summary))]
    [(str "lowest coverage "
          (str/join ", "
                    (map (fn [{:keys [namespace line-coverage]}]
                           (format "%s %.2f%%" namespace line-coverage))
                         (take 3 lowest))))]))

(defn gate->report-gate
  [gate]
  (assoc gate
         :evidence (vec (concat (map issue->evidence (:issues gate))
                                (map finding->evidence (:findings gate))
                                (summary->evidence (:summary gate))))))

(def overall-status
  governance/overall-status)

(defn print-report!
  [gates]
  (report/print-report! (map gate->report-gate gates)))
