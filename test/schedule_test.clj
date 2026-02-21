(ns lab-test.schedule
  "Integration tests for schedule command"
  (:require [clojure.test :refer [deftest is testing use-fixtures run-tests]]
            [babashka.fs :as fs]
            [clojure.string :as str]))

;; Load lab namespace
(load-file "lab.bb")

;; Test fixtures - use temp directory to avoid polluting real data/
(def test-data-dir "test/data-schedule-test")

(defn setup-test-dir [f]
  (fs/create-dirs test-data-dir)
  ;; Override the file paths for testing
  (with-redefs [lab/lab-crontab-file (str test-data-dir "/lab-crontab")
                lab/crontab-backup-file (str test-data-dir "/crontab.backup")]
    (try
      (f)
      (finally
        (fs/delete-tree test-data-dir)))))

(use-fixtures :each setup-test-dir)

;; Mock crontab state
(def ^:dynamic *mock-crontab* "")

(defn with-mock-crontab [initial-content f]
  (binding [*mock-crontab* initial-content]
    (with-redefs [lab/get-crontab (fn [] *mock-crontab*)
                  babashka.process/shell
                  (fn [opts & args]
                    (if (and (map? opts) (:in opts)
                             (= ["crontab" "-"] (vec args)))
                      ;; Capture crontab -
                      (do (set! *mock-crontab* (:in opts))
                          {:exit 0})
                      ;; Pass through other commands
                      (apply babashka.process/shell opts args)))]
      (f))))

(deftest generate-crontab-entries-test
  (testing "generates entries for scheduled integrations"
    (let [config {:integrations
                  {:daily-task {:handler "foo.clj" :schedule "0 8 * * *"}
                   :weekly-task {:handler "bar.clj" :schedule "0 0 * * 0"}
                   :no-schedule {:handler "baz.clj"}}}
          entries (lab/generate-crontab-entries config)]
      (is (= 4 (count entries))) ; 2 comments + 2 commands
      (is (some #(str/includes? % "# lab:daily-task") entries))
      (is (some #(str/includes? % "0 8 * * *") entries))
      (is (some #(str/includes? % "# lab:weekly-task") entries))
      (is (some #(str/includes? % "0 0 * * 0") entries))
      ;; no-schedule should not appear
      (is (not (some #(str/includes? % "no-schedule") entries)))))

  (testing "returns empty for no scheduled integrations"
    (let [config {:integrations {:foo {:handler "foo.clj"}}}
          entries (lab/generate-crontab-entries config)]
      (is (empty? entries)))))

(deftest remove-lab-entries-test
  (testing "removes lab entries from crontab"
    (let [crontab "# user entry\n0 5 * * * backup.sh\n# lab:foo\n0 8 * * * cd /opt && ./lab run foo"
          cleaned (lab/remove-lab-entries crontab)]
      (is (str/includes? cleaned "backup.sh"))
      (is (not (str/includes? cleaned "lab:foo")))
      (is (not (str/includes? cleaned "./lab run")))))

  (testing "preserves crontab with no lab entries"
    (let [crontab "0 5 * * * backup.sh\n0 6 * * * other.sh"
          cleaned (lab/remove-lab-entries crontab)]
      (is (str/includes? cleaned "backup.sh"))
      (is (str/includes? cleaned "other.sh")))))

(deftest schedule-install-creates-files-test
  (testing "install creates lab-crontab and backup files"
    (with-mock-crontab "# existing\n0 5 * * * backup.sh"
      (fn []
        (let [config {:integrations {:test-job {:handler "test.clj" :schedule "0 9 * * *"}}}]
          ;; Capture stdout
          (let [output (with-out-str (lab/cmd-schedule config "install"))]
            ;; Check lab-crontab file created
            (is (fs/exists? (str test-data-dir "/lab-crontab")))
            (let [lab-content (slurp (str test-data-dir "/lab-crontab"))]
              (is (str/includes? lab-content "# lab:test-job"))
              (is (str/includes? lab-content "0 9 * * *")))

            ;; Check backup created
            (is (fs/exists? (str test-data-dir "/crontab.backup")))
            (let [backup-content (slurp (str test-data-dir "/crontab.backup"))]
              (is (str/includes? backup-content "backup.sh"))
              (is (not (str/includes? backup-content "lab:"))))))))))

(deftest schedule-install-merges-with-existing-test
  (testing "install preserves existing crontab entries"
    (with-mock-crontab "0 5 * * * backup.sh"
      (fn []
        (let [config {:integrations {:my-job {:handler "x.clj" :schedule "0 10 * * *"}}}]
          (lab/cmd-schedule config "install")
          ;; Mock crontab should contain both
          (is (str/includes? *mock-crontab* "backup.sh"))
          (is (str/includes? *mock-crontab* "0 10 * * *")))))))

(deftest schedule-remove-restores-backup-test
  (testing "remove restores from backup file"
    (with-mock-crontab "0 5 * * * backup.sh\n# lab:foo\n0 8 * * * ./lab run foo"
      (fn []
        ;; Create a backup file simulating previous install
        (spit (str test-data-dir "/crontab.backup") "0 5 * * * backup.sh\n")
        (spit (str test-data-dir "/lab-crontab") "# lab:foo\n0 8 * * * ./lab run foo")

        (lab/cmd-schedule {} "remove")

        ;; Should restore from backup
        (is (str/includes? *mock-crontab* "backup.sh"))
        (is (not (str/includes? *mock-crontab* "lab:foo")))
        ;; lab-crontab should be deleted
        (is (not (fs/exists? (str test-data-dir "/lab-crontab"))))))))

(deftest schedule-show-prints-entries-test
  (testing "show prints crontab entries without modifying anything"
    (let [config {:integrations {:show-test {:handler "x.clj" :schedule "0 12 * * *"}}}
          output (with-out-str (lab/cmd-schedule config "show"))]
      (is (str/includes? output "# lab:show-test"))
      (is (str/includes? output "0 12 * * *"))
      ;; Should not create any files
      (is (not (fs/exists? (str test-data-dir "/lab-crontab"))))
      (is (not (fs/exists? (str test-data-dir "/crontab.backup")))))))

(deftest schedule-idempotent-test
  (testing "multiple installs don't duplicate entries"
    (with-mock-crontab ""
      (fn []
        (let [config {:integrations {:idem-test {:handler "x.clj" :schedule "0 7 * * *"}}}]
          ;; Install twice
          (lab/cmd-schedule config "install")
          (lab/cmd-schedule config "install")

          ;; Should only have one occurrence
          (let [occurrences (count (re-seq #"lab:idem-test" *mock-crontab*))]
            (is (= 1 occurrences))))))))

;; Run tests when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
