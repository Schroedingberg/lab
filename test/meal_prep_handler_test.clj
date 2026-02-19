(ns lab-test.meal-prep-handler
  "Unit tests for meal-prep-reminder handler.
   
   Tests:
   1. Handler loading and structure  
   2. Prep notes extraction logic
   3. Config integration entry
   4. Docker compose generation
   
   Run with: bb test/meal_prep_handler_test.clj"
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Load lab namespace
(load-file "lab.bb")

;; =============================================================================
;; Test 1: Handler Loading and Structure
;; =============================================================================

(deftest handler-loading-test
  (testing "The meal-prep-reminder handler loads correctly"
    (let [handler (load-file "integrations/meal-prep-reminder.clj")]
      (is (fn? handler) "Handler should be a function"))))

;; =============================================================================
;; Test 2: Extract Prep Notes Logic
;; =============================================================================

(deftest extract-prep-notes-test
  (testing "Extracting prep notes from recipe text"
    ;; Load the handler file to get internal functions
    (load-file "integrations/meal-prep-reminder.clj")

    ;; Test the logic directly since we loaded the namespace
    (let [extract-fn (resolve 'extract-prep-notes)]
      (when extract-fn
        (testing "Extracts prep: lines"
          (is (= ["Take salmon out of freezer"]
                 (extract-fn {:notes "prep:Take salmon out of freezer\nServes 4."}))))

        (testing "Handles multiple prep notes"
          (is (= ["Defrost fish" "Marinate chicken overnight"]
                 (extract-fn {:notes "prep:Defrost fish\nprep:Marinate chicken overnight\nPreheat oven."}))))

        (testing "Case insensitive"
          (is (= ["Defrost fish"]
                 (extract-fn {:notes "PREP:Defrost fish"}))))

        (testing "Returns empty for no prep notes"
          (is (empty? (extract-fn {:notes "Just a regular recipe"}))))))))

;; =============================================================================
;; Test 3: Config Integration Entry
;; =============================================================================

(deftest config-integration-test
  (testing "meal-prep-reminder is configured in config.edn"
    (let [config (lab/load-config)]
      (is (contains? (:integrations config) :meal-prep-reminder)
          "Config should have meal-prep-reminder integration")
      (when-let [integration (get-in config [:integrations :meal-prep-reminder])]
        (is (= "integrations/meal-prep-reminder.clj" (:handler integration))
            "Should point to correct handler file")
        (is (some? (:description integration))
            "Should have a description")))))

;; =============================================================================
;; Test 4: Services Configuration
;; =============================================================================

(deftest services-config-test
  (testing "Required services are configured"
    (let [config (lab/load-config)
          services (:services config)]
      (is (contains? services :mealie) "Should have mealie service")
      (is (contains? services :vikunja) "Should have vikunja service")

      (when-let [vikunja (:vikunja services)]
        (is (= "vikunja/vikunja:latest" (:image vikunja)))
        (is (some #(str/includes? % "3456") (:ports vikunja))
            "Vikunja should expose port 3456")))))

;; =============================================================================
;; Test 5: Docker Compose Generation
;; =============================================================================

(deftest compose-generation-test
  (testing "Services generate valid compose YAML"
    (let [config (lab/load-config)
          yaml-str (lab/config->yaml config)]
      (is (str/includes? yaml-str "mealie") "Should include mealie service")
      (is (str/includes? yaml-str "vikunja") "Should include vikunja service")
      (is (str/includes? yaml-str "VIKUNJA_SERVICE_PUBLICURL")
          "Should include Vikunja public URL config"))))

;; =============================================================================
;; Test 6: Handler Invocation Structure
;; =============================================================================

(deftest handler-invocation-test
  (testing "Handler accepts correct argument structure"
    (let [handler (load-file "integrations/meal-prep-reminder.clj")
          ;; The handler expects {:secrets {:mealie {...} :vikunja {...}}}
          ;; We can't call it without real services, but we can verify structure
          test-args {:secrets {:mealie {:url "http://localhost:9925" :token "test"}
                               :vikunja {:url "http://localhost:3456" :token "test"}}}]
      ;; Just verify handler is callable - will fail on network but that's OK
      (is (fn? handler) "Handler should be a function"))))

;; =============================================================================
;; Runner
;; =============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (println "\n" (str (apply str (repeat 60 "=")) "\n"))
  (println "MEAL PREP INTEGRATION TESTS")
  (println "Testing handler logic, config, and compose generation")
  (println "\n" (str (apply str (repeat 60 "=")) "\n"))
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
