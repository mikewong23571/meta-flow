(ns meta-flow.lint.check.report
  (:require [clojure.string :as str]
            [meta-flow.lint.file-length :as file-length]))

(defn issue->evidence
  [issue]
  (case (:kind issue)
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

(defn overall-status
  [gates]
  (cond
    (some #(= :error (:status %)) gates) :blocked
    (some #(= :warning (:status %)) gates) :warning
    :else :pass))

(defn print-gate-group!
  [title gates]
  (when (seq gates)
    (println (str title ":"))
    (doseq [gate gates]
      (println (str "- " (:label gate) ": " (:headline gate)))
      (when-let [cause (:cause gate)]
        (println (str "  cause: " cause)))
      (when-let [issues (seq (:issues gate))]
        (println (str "  evidence: "
                      (str/join "; " (map issue->evidence issues)))))
      (when-let [findings (seq (:findings gate))]
        (println (str "  evidence: "
                      (str/join "; "
                                (map (fn [{:keys [filename row type message]}]
                                       (str filename ":" row " [" type "] " message))
                                     findings)))))
      (when-let [summary (:summary gate)]
        (when-let [lowest (seq (:lowest-namespaces summary))]
          (println (str "  evidence: lowest coverage "
                        (str/join ", "
                                  (map (fn [{:keys [namespace line-coverage]}]
                                         (format "%s %.2f%%" namespace line-coverage))
                                       (take 3 lowest)))))))
      (println (str "  action: " (:action gate))))))

(defn print-report!
  [gates]
  (let [status (overall-status gates)
        blocked (filter #(= :error (:status %)) gates)
        warnings (filter #(= :warning (:status %)) gates)
        passed (filter #(= :pass (:status %)) gates)
        skipped (filter #(= :skipped (:status %)) gates)]
    (println (str "AI governance summary: " (name status)))
    (print-gate-group! "blocked gates" blocked)
    (print-gate-group! "warning gates" warnings)
    (when (seq passed)
      (println "passed gates:")
      (doseq [gate passed]
        (println (str "- " (:label gate) ": " (:headline gate)))))
    (when (seq skipped)
      (println "skipped gates:")
      (doseq [gate skipped]
        (println (str "- " (:label gate) ": " (:headline gate)))))
    (flush)))
