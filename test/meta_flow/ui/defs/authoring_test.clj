(ns meta-flow.ui.defs.authoring-test
  (:require [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.authoring :as defs.authoring]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.ui.defs.authoring :as ui.defs.authoring]))

(deftest zero-arity-authoring-helpers-use-the-default-definition-repository
  (let [calls (atom [])]
    (with-redefs [defs.loader/filesystem-definition-repository (fn [] ::defs-repo)
                  defs.authoring/list-definition-templates (fn [defs-repo definition-kind]
                                                             (swap! calls conj [:templates defs-repo definition-kind])
                                                             {:definition-kind definition-kind})
                  defs.authoring/list-definition-drafts (fn [defs-repo definition-kind]
                                                          (swap! calls conj [:drafts defs-repo definition-kind])
                                                          {:definition-kind definition-kind})
                  defs.authoring/load-definition-draft (fn [defs-repo definition-kind definition-ref]
                                                         (swap! calls conj [:draft defs-repo definition-kind definition-ref])
                                                         {:definition-kind definition-kind
                                                          :definition-ref definition-ref})]
      (is (= {:definition-kind :runtime-profile}
             (ui.defs.authoring/list-runtime-profile-templates)))
      (is (= {:definition-kind :task-type}
             (ui.defs.authoring/list-task-type-templates)))
      (is (= {:definition-kind :runtime-profile}
             (ui.defs.authoring/list-runtime-profile-drafts)))
      (is (= {:definition-kind :task-type}
             (ui.defs.authoring/list-task-type-drafts)))
      (is (= {:definition-kind :runtime-profile
              :definition-ref {:definition/id :runtime-profile/repo-review
                               :definition/version 2}}
             (ui.defs.authoring/load-runtime-profile-draft :runtime-profile/repo-review 2)))
      (is (= {:definition-kind :task-type
              :definition-ref {:definition/id :task-type/repo-review
                               :definition/version 3}}
             (ui.defs.authoring/load-task-type-draft :task-type/repo-review 3)))
      (is (= [[:templates ::defs-repo :runtime-profile]
              [:templates ::defs-repo :task-type]
              [:drafts ::defs-repo :runtime-profile]
              [:drafts ::defs-repo :task-type]
              [:draft ::defs-repo :runtime-profile {:definition/id :runtime-profile/repo-review
                                                    :definition/version 2}]
              [:draft ::defs-repo :task-type {:definition/id :task-type/repo-review
                                              :definition/version 3}]]
             @calls)))))

(deftest mutating-authoring-helpers-delegate-and-reload-published-definitions
  (let [calls (atom [])]
    (with-redefs [defs.authoring/authoring-contract (fn []
                                                      (swap! calls conj [:contract])
                                                      {:contract :ok})
                  defs.authoring/prepare-runtime-profile-draft-request! (fn [defs-repo request]
                                                                          (swap! calls conj [:validate-runtime defs-repo request])
                                                                          {:request request})
                  defs.authoring/create-runtime-profile-draft! (fn [defs-repo request]
                                                                 (swap! calls conj [:create-runtime defs-repo request])
                                                                 {:created request})
                  defs.authoring/prepare-task-type-draft-request! (fn [defs-repo request]
                                                                    (swap! calls conj [:validate-task defs-repo request])
                                                                    {:request request})
                  defs.authoring/create-task-type-draft! (fn [defs-repo request]
                                                           (swap! calls conj [:create-task defs-repo request])
                                                           {:created request})
                  defs.authoring/publish-runtime-profile-draft! (fn [defs-repo definition-ref]
                                                                  (swap! calls conj [:publish-runtime defs-repo definition-ref])
                                                                  {:published :runtime-profile
                                                                   :definition-ref definition-ref})
                  defs.authoring/publish-task-type-draft! (fn [defs-repo definition-ref]
                                                            (swap! calls conj [:publish-task defs-repo definition-ref])
                                                            {:published :task-type
                                                             :definition-ref definition-ref})
                  defs.loader/reload-filesystem-definition-repository! (fn [defs-repo]
                                                                         (swap! calls conj [:reload defs-repo])
                                                                         {:task-types [1 2]
                                                                          :runtime-profiles [1]})
                  defs.loader/definitions-summary (fn [definitions]
                                                    (swap! calls conj [:summary definitions])
                                                    {:definition-count (count (concat (:task-types definitions)
                                                                                      (:runtime-profiles definitions)))})]
      (testing "facade functions return delegated results"
        (is (= {:contract :ok}
               (ui.defs.authoring/authoring-contract)))
        (is (= {:request {:id 1}}
               (ui.defs.authoring/validate-runtime-profile-draft-request! ::defs-repo {:id 1})))
        (is (= {:created {:id 2}}
               (ui.defs.authoring/create-runtime-profile-draft! ::defs-repo {:id 2})))
        (is (= {:request {:id 3}}
               (ui.defs.authoring/validate-task-type-draft-request! ::defs-repo {:id 3})))
        (is (= {:created {:id 4}}
               (ui.defs.authoring/create-task-type-draft! ::defs-repo {:id 4}))))
      (testing "reload and publish include refreshed definition summaries"
        (is (= {:status "ok"
                :definitions {:definition-count 3}}
               (ui.defs.authoring/reload-definition-repository! ::defs-repo)))
        (is (= {:published :runtime-profile
                :definition-ref {:definition/id :runtime-profile/repo-review
                                 :definition/version 1}
                :reload {:status "ok"
                         :definitions {:definition-count 3}}}
               (ui.defs.authoring/publish-runtime-profile-draft! ::defs-repo
                                                                 {:definition/id :runtime-profile/repo-review
                                                                  :definition/version 1})))
        (is (= {:published :task-type
                :definition-ref {:definition/id :task-type/repo-review
                                 :definition/version 1}
                :reload {:status "ok"
                         :definitions {:definition-count 3}}}
               (ui.defs.authoring/publish-task-type-draft! ::defs-repo
                                                           {:definition/id :task-type/repo-review
                                                            :definition/version 1}))))
      (is (= [[:contract]
              [:validate-runtime ::defs-repo {:id 1}]
              [:create-runtime ::defs-repo {:id 2}]
              [:validate-task ::defs-repo {:id 3}]
              [:create-task ::defs-repo {:id 4}]
              [:reload ::defs-repo]
              [:summary {:task-types [1 2]
                         :runtime-profiles [1]}]
              [:publish-runtime ::defs-repo {:definition/id :runtime-profile/repo-review
                                             :definition/version 1}]
              [:reload ::defs-repo]
              [:summary {:task-types [1 2]
                         :runtime-profiles [1]}]
              [:publish-task ::defs-repo {:definition/id :task-type/repo-review
                                          :definition/version 1}]
              [:reload ::defs-repo]
              [:summary {:task-types [1 2]
                         :runtime-profiles [1]}]]
             @calls)))))
