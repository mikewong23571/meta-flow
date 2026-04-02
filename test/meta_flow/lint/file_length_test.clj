(ns meta-flow.lint.file-length-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.lint.file-length :as file-length]))

(deftest classify-line-count-uses-warning-and-error-thresholds
  (is (nil? (file-length/classify-line-count 240)))
  (is (= :warning (file-length/classify-line-count 241)))
  (is (= :warning (file-length/classify-line-count 300)))
  (is (= :error (file-length/classify-line-count 301))))

(deftest classify-directory-file-count-uses-warning-and-error-thresholds
  (is (nil? (file-length/classify-directory-file-count 7)))
  (is (= :warning (file-length/classify-directory-file-count 8)))
  (is (= :warning (file-length/classify-directory-file-count 12)))
  (is (= :error (file-length/classify-directory-file-count 13))))

(deftest issue-message-explains-governance-intent
  (testing "warning copy explains the responsibility review intent"
    (let [message (file-length/issue-message {:path "src/meta_flow/example.clj"
                                              :line-count 250
                                              :level :warning})]
      (is (str/includes? message "WARNING [file-length-governance]"))
      (is (str/includes? message "250 lines"))
      (is (str/includes? message "threshold of 240 lines"))
      (is (str/includes? message "avoid a single namespace accumulating too much responsibility"))
      (is (str/includes? message "consider splitting the file by responsibility"))))
  (testing "error copy explains that lint is blocked until the file is split"
    (let [message (file-length/issue-message {:path "src/meta_flow/example.clj"
                                              :line-count 320
                                              :level :error})]
      (is (str/includes? message "ERROR [file-length-governance]"))
      (is (str/includes? message "threshold of 300 lines"))
      (is (str/includes? message "blocks lint"))
      (is (str/includes? message "splitting the file by responsibility")))))

(deftest directory-issue-message-explains-layering-intent
  (testing "warning copy explains the sibling namespace governance intent"
    (let [message (file-length/issue-message {:kind :directory-width
                                              :path "src/meta_flow/store/sqlite"
                                              :file-count 9
                                              :level :warning})]
      (is (str/includes? message "WARNING [directory-width-governance]"))
      (is (str/includes? message "9 direct Clojure source files"))
      (is (str/includes? message "threshold of 7 files"))
      (is (str/includes? message "too many sibling namespaces"))
      (is (str/includes? message "consider introducing subdirectories by responsibility"))))
  (testing "error copy explains that lint is blocked until the directory is narrowed"
    (let [message (file-length/issue-message {:kind :directory-width
                                              :path "src/meta_flow/store/sqlite"
                                              :file-count 14
                                              :level :error})]
      (is (str/includes? message "ERROR [directory-width-governance]"))
      (is (str/includes? message "threshold of 12 files"))
      (is (str/includes? message "blocks lint"))
      (is (str/includes? message "splitting responsibilities into smaller directory layers")))))
