(ns meta-flow.governance.report
  (:require [meta-flow.governance.core :as core]))

(defn print-gate-group!
  [title gates]
  (when (seq gates)
    (println (str title ":"))
    (doseq [gate gates]
      (println (str "- " (:label gate) ": " (:headline gate)))
      (when-let [cause (:cause gate)]
        (println (str "  cause: " cause)))
      (doseq [evidence-line (:evidence gate)]
        (println (str "  evidence: " evidence-line)))
      (println (str "  action: " (:action gate))))))

(defn print-report!
  [gates]
  (let [status (core/overall-status gates)
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
