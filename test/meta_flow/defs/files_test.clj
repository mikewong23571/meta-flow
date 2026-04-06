(ns meta-flow.defs.files-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.source :as defs.source]
            [meta-flow.defs.workspace.files :as workspace.files]))

(defn- temp-overlay-root
  []
  (.getPath (.toFile (java.nio.file.Files/createTempDirectory "meta-flow-defs-files"
                                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest atomic-write-creates-parent-directories-and-replaces-content
  (let [overlay-root (temp-overlay-root)
        target-file (io/file overlay-root "nested" "runtime-profiles.edn")]
    (workspace.files/atomic-write! (.getPath target-file) "[]\n")
    (is (.exists target-file))
    (is (= "[]\n" (slurp target-file)))
    (workspace.files/atomic-write! (.getPath target-file) "[{:runtime-profile/id :runtime-profile/test}]\n")
    (is (= "[{:runtime-profile/id :runtime-profile/test}]\n"
           (slurp target-file)))))

(deftest initialize-overlay-creates-deterministic-files-and-is-idempotent
  (let [overlay-root (temp-overlay-root)
        initial (workspace.files/initialize-overlay! overlay-root)
        task-types-path (io/file overlay-root "task-types.edn")
        expected-created-count (count defs.source/additive-definition-keys)]
    (testing "the first initialization creates active files and draft directories"
      (is (= overlay-root (:overlay-root initial)))
      (is (= (workspace.files/draft-root-path overlay-root)
             (:draft-root initial)))
      (is (= expected-created-count
             (count (:created-files initial))))
      (doseq [definition-key defs.source/additive-definition-keys]
        (is (.exists (io/file (workspace.files/overlay-file-path overlay-root definition-key))))
        (is (.isDirectory (io/file (workspace.files/draft-directory-path overlay-root definition-key))))))
    (testing "active files are initialized as empty vectors"
      (is (= "[]\n" (slurp task-types-path))))
    (testing "a second initialization preserves existing files"
      (spit task-types-path "[{:task-type/id :task-type/preserved}]\n")
      (let [second (workspace.files/initialize-overlay! overlay-root)]
        (is (= []
               (:created-files second)))
        (is (= "[{:task-type/id :task-type/preserved}]\n"
               (slurp task-types-path)))
        (is (str/ends-with? (:draft-root second) "/drafts"))))))
