(ns lab-test.e2e
  "End-to-end tests - full workflow with real services"
  (:require [clojure.test :refer [deftest is testing use-fixtures run-tests]]
            [babashka.process :refer [shell process check]]
            [babashka.http-client :as http]
            [cheshire.core :as json]))

;; Load lab namespace
(load-file "lab")

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

(deftest full-integration-workflow-test
  (testing "complete flow: start service, run integration, verify"
    (let [config {:services {:httpbin {:image "kennethreitz/httpbin"
                                       :ports ["18082:80"]}}
                  :integrations {:test-post
                                 {:type :http-call
                                  :action {:method :post
                                           :url "http://localhost:18082/post"
                                           :headers {:Content-Type "application/json"}
                                           :body {:test "data"}}}}}
          yaml-str (lab/config->yaml config)
          project "lab-e2e-test"]
      (try
        ;; Start services
        (println "  Starting httpbin...")
        (shell {:in yaml-str :out :string}
               "docker" "compose" "-f" "-" "-p" project "up" "-d")

        ;; Wait for httpbin to be ready
        (Thread/sleep 5000)

        ;; Test the integration would work
        (println "  Running HTTP integration...")
        (let [integration (get-in config [:integrations :test-post])
              result (lab/run-http-integration! integration)]
          (is (= :ok result)))

        (finally
          (println "  Cleaning up...")
          (shell {:in yaml-str :out :string :continue true}
                 "docker" "compose" "-f" "-" "-p" project "down"))))))

(deftest code-handler-loads-test
  (testing "code-based integration handler loads and runs"
    ;; This tests the handler loading mechanism without needing external services
    (let [;; Create a simple test handler
          handler-code "(fn [config] (println \"  Handler executed!\") :ok)"
          handler-path "/tmp/test-handler.clj"
          _ (spit handler-path handler-code)

          integration {:handler handler-path}
          config {:secrets {:test "value"}}]

      (is (= :ok (lab/run-code-integration! config integration))))))

;; Run tests when executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
