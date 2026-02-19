(ns lab-test.integration
  "Integration tests - require Docker"
  (:require [clojure.test :refer [deftest is testing use-fixtures run-tests]]
            [babashka.process :refer [shell process check]]
            [clojure.string :as str]))

;; Load lab namespace
(load-file "lab.bb")

(defn docker-available? []
  (try
    (-> (process ["docker" "info"] {:out :string :err :string})
        check)
    true
    (catch Exception _ false)))

(defn skip-if-no-docker [f]
  (if (docker-available?)
    (f)
    (println "SKIP: Docker not available")))

(use-fixtures :once skip-if-no-docker)

(deftest compose-via-stdin-test
  (testing "can start and stop services via stdin"
    (let [config {:services {:test-nginx {:image "nginx:alpine"
                                          :ports ["18080:80"]}}}
          yaml-str (lab/config->yaml config)
          project "lab-integration-test"]
      (try
        ;; Start
        (shell {:in yaml-str :out :string}
               "docker" "compose" "-f" "-" "-p" project "up" "-d")
        (Thread/sleep 2000)

        ;; Verify running
        (let [result (shell {:in yaml-str :out :string}
                            "docker" "compose" "-f" "-" "-p" project "ps")]
          (is (zero? (:exit result))))

        (finally
          ;; Cleanup
          (shell {:in yaml-str :out :string :continue true}
                 "docker" "compose" "-f" "-" "-p" project "down"))))))

(deftest compose-multiple-services-test
  (testing "can manage multiple services"
    (let [config {:services {:web {:image "nginx:alpine" :ports ["18081:80"]}
                             :redis {:image "redis:alpine"}}}
          yaml-str (lab/config->yaml config)
          project "lab-multi-test"]
      (try
        (shell {:in yaml-str :out :string}
               "docker" "compose" "-f" "-" "-p" project "up" "-d")
        (Thread/sleep 3000)

        (let [result (shell {:in yaml-str :out :string}
                            "docker" "compose" "-f" "-" "-p" project "ps" "--format" "json")]
          (is (zero? (:exit result)))
          (is (str/includes? (:out result) "web"))
          (is (str/includes? (:out result) "redis")))

        (finally
          (shell {:in yaml-str :out :string :continue true}
                 "docker" "compose" "-f" "-" "-p" project "down"))))))

;; Run tests when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
