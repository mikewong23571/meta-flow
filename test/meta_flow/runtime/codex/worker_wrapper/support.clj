(ns meta-flow.runtime.codex.worker-wrapper.support)

(defn fake-process
  [{:keys [wait-results exit-code on-destroy pid]
    :or {pid 4242}}]
  (let [wait-state (atom wait-results)
        destroyed? (atom false)]
    (proxy [Process] []
      (getOutputStream []
        (java.io.ByteArrayOutputStream.))
      (getInputStream []
        (java.io.ByteArrayInputStream. (byte-array 0)))
      (getErrorStream []
        (java.io.ByteArrayInputStream. (byte-array 0)))
      (waitFor
        ([]
         (while (not (or @destroyed?
                         (true? (first @wait-state))))
           (Thread/sleep 1))
         (long (or exit-code 0)))
        ([timeout _unit]
         (let [result (if @destroyed?
                        true
                        (boolean (first @wait-state)))]
           (when (seq @wait-state)
             (swap! wait-state #(if (next %) (vec (rest %)) %)))
           result)))
      (exitValue []
        (int (or exit-code 0)))
      (pid []
        (long pid))
      (destroy []
        (reset! destroyed? true)
        (when on-destroy
          (on-destroy)))
      (destroyForcibly []
        (reset! destroyed? true)
        (when on-destroy
          (on-destroy))
        this)
      (isAlive []
        (not (or @destroyed?
                 (true? (first @wait-state))))))))
