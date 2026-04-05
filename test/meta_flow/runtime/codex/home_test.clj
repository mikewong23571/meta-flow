(ns meta-flow.runtime.codex.home-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [meta-flow.defs.loader :as defs.loader]
            [meta-flow.runtime.codex :as codex]
            [meta-flow.runtime.codex.fs :as codex.fs]
            [meta-flow.runtime.codex.home :as codex.home]
            [meta-flow.runtime.codex.test-support :as codex.support]
            [meta-flow.runtime.protocol :as runtime.protocol]
            [meta-flow.scheduler.support.test-support :as support]
            [meta-flow.store.sqlite :as store.sqlite]))

(deftest codex-home-install-is-idempotent-and-preserves-existing-files
  (let [{:keys [codex-home-dir]} (support/temp-system)
        repository (defs.loader/filesystem-definition-repository)
        runtime-profile (codex.support/codex-profile-with-temp-home repository codex-home-dir)
        missing-config (str codex-home-dir "-missing-config.toml")]
    (with-redefs [codex.home/user-codex-config-file (constantly missing-config)]
      (let [first-install (codex.home/install-home! runtime-profile)
            readme-path (str codex-home-dir "/README.md")
            _ (spit readme-path "user customized README\n")
            second-install (codex.home/install-home! runtime-profile)]
        (testing "first install materializes bundled templates"
          (is (= (.getCanonicalPath (io/file codex-home-dir))
                 (:codex-home/root first-install)))
          (is (= 2 (count (:codex-home/installed-paths first-install))))
          (is (.exists (io/file codex-home-dir "README.md")))
          (is (.exists (io/file codex-home-dir "config.edn"))))
        (testing "reinstall preserves existing files instead of overwriting them"
          (is (= "user customized README\n"
                 (slurp readme-path)))
          (is (empty? (:codex-home/installed-paths second-install)))
          (is (= 2 (count (:codex-home/skipped-paths second-install)))))
        (testing "skill keys always present even when profile has no skills"
          (is (= [] (:codex-home/skills-installed first-install)))
          (is (= [] (:codex-home/skills-skipped first-install)))
          (is (= [] (:codex-home/skills-not-found first-install))))))))

(deftest codex-home-install-copies-user-config-toml-when-available
  (let [{:keys [codex-home-dir]} (support/temp-system)
        fake-user-config (str codex-home-dir "-user-config.toml")
        profile {:runtime-profile/codex-home-root codex-home-dir}]
    (spit fake-user-config "model_provider = \"x\"\n")
    (with-redefs [codex.home/user-codex-config-file (constantly fake-user-config)]
      (let [first-install (codex.home/install-home! profile)
            installed-config (io/file codex-home-dir "config.toml")]
        (testing "first install copies the user's Codex config"
          (is (.exists installed-config))
          (is (= "model_provider = \"x\"\n"
                 (slurp installed-config)))
          (is (some #(.endsWith % "/config.toml")
                    (:codex-home/installed-paths first-install)))))
      (let [installed-config (io/file codex-home-dir "config.toml")
            _ (spit fake-user-config "model_provider = \"updated\"\n")
            second-install (codex.home/install-home! profile)]
        (testing "reinstall resyncs config.toml from the user's Codex config"
          (is (= "model_provider = \"updated\"\n"
                 (slurp installed-config)))
          (is (some #(.endsWith % "/config.toml")
                    (:codex-home/installed-paths second-install))))))))

(deftest codex-home-install-copies-skills-from-user-codex-home
  (let [{:keys [codex-home-dir]} (support/temp-system)
        fake-user-skills-dir (str codex-home-dir "-user-skills")
        skill-name "test-skill"
        skill-src  (io/file fake-user-skills-dir skill-name)
        _          (.mkdirs skill-src)
        _          (spit (io/file skill-src "SKILL.md") "# test-skill\n")
        profile    {:runtime-profile/codex-home-root codex-home-dir
                    :runtime-profile/skills [skill-name]}]
    (with-redefs [codex.home/user-codex-config-file (constantly (str codex-home-dir "-missing-config.toml"))
                  codex.home/user-codex-skills-root (constantly fake-user-skills-dir)]
      (testing "skill is copied from user codex home on first install"
        (let [result (codex.home/install-home! profile)]
          (is (= [skill-name] (:codex-home/skills-installed result)))
          (is (= [] (:codex-home/skills-skipped result)))
          (is (= [] (:codex-home/skills-not-found result)))
          (is (.exists (io/file codex-home-dir "skills" skill-name "SKILL.md")))))
      (testing "skill is resynced on reinstall"
        (spit (io/file skill-src "SKILL.md") "# test-skill v2\n")
        (let [result (codex.home/install-home! profile)]
          (is (= [skill-name] (:codex-home/skills-installed result)))
          (is (= [] (:codex-home/skills-skipped result)))
          (is (= "# test-skill v2\n"
                 (slurp (io/file codex-home-dir "skills" skill-name "SKILL.md"))))))
      (testing "missing required skill fails fast"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"missing required skills"
                              (codex.home/install-home!
                               (assoc profile
                                      :runtime-profile/id :runtime-profile/requires-skill
                                      :runtime-profile/skills ["no-such-skill"])))))
      (testing "the failure identifies the missing skills"
        (try
          (codex.home/install-home!
           (assoc profile
                  :runtime-profile/id :runtime-profile/requires-skill
                  :runtime-profile/skills ["no-such-skill"]))
          (is false)
          (catch clojure.lang.ExceptionInfo ex
            (is (= :runtime-profile/requires-skill
                   (get-in (ex-data ex) [:runtime-profile/id])))
            (is (= ["no-such-skill"]
                   (:codex-home/skills-not-found (ex-data ex))))))))))

(deftest prepare-run-writes-concrete-codex-worker-snapshot
  (let [{:keys [db-path artifacts-dir runs-dir codex-home-dir]} (support/temp-system)
        repository (codex.support/repository-with-temp-codex-home
                    (defs.loader/filesystem-definition-repository)
                    codex-home-dir)
        adapter (codex/codex-runtime)
        task (support/enqueue-codex-task! db-path {:work-key "CVE-2024-CODEX-SNAPSHOT"})
        run {:run/id "run-codex-snapshot"
             :run/task-id (:task/id task)
             :run/attempt 1
             :run/run-fsm-ref (:task/run-fsm-ref task)
             :run/runtime-profile-ref (:task/runtime-profile-ref task)
             :run/state :run.state/leased
             :run/created-at "2026-04-03T00:00:00Z"
             :run/updated-at "2026-04-03T00:00:00Z"}]
    (binding [codex.fs/*artifact-root-dir* artifacts-dir
              codex.fs/*run-root-dir* runs-dir]
      (with-redefs [defs.loader/filesystem-definition-repository (fn
                                                                   ([] repository)
                                                                   ([_] repository))]
        (let [ctx {:db-path db-path
                   :store (store.sqlite/sqlite-state-store db-path)
                   :repository repository
                   :now "2026-04-03T00:00:00Z"}
              _ (runtime.protocol/prepare-run! adapter ctx task run)
              workdir (str runs-dir "/" (:run/id run))
              definitions (edn/read-string (slurp (str workdir "/definitions.edn")))
              runtime-profile (edn/read-string (slurp (str workdir "/runtime-profile.edn")))
              artifact-contract (edn/read-string (slurp (str workdir "/artifact-contract.edn")))
              worker-prompt (slurp (str workdir "/worker-prompt.md"))]
          (testing "prepare-run writes concrete, pinned worker inputs"
            (is (= :task-type/cve-investigation
                   (get-in definitions [:task-type :task-type/id])))
            (is (= :artifact-contract/cve-investigation
                   (get-in artifact-contract [:artifact-contract/id])))
            (is (= (.getCanonicalPath (io/file codex-home-dir))
                   (:runtime-profile/codex-home-root runtime-profile)))
            (is (= (.getCanonicalPath (io/file "script/worker_api.bb"))
                   (:runtime-profile/helper-script-path runtime-profile)))
            (is (= (.getCanonicalPath (io/file workdir "worker-prompt.md"))
                   (:runtime-profile/worker-prompt-path runtime-profile)))
            (is (.exists (io/file workdir "task.edn")))
            (is (.exists (io/file workdir "run.edn")))
            (is (.exists (io/file workdir "worker-prompt.md")))
            (is (.contains worker-prompt
                           (str "Artifact root: `"
                                (.getCanonicalPath (io/file artifacts-dir
                                                            (:task/id task)
                                                            (:run/id run)))
                                "`")))
            (is (.contains worker-prompt
                           (str "Run log path: `"
                                (.getCanonicalPath (io/file artifacts-dir
                                                            (:task/id task)
                                                            (:run/id run)
                                                            "run.log"))
                                "`")))))))))
