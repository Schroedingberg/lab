#!/usr/bin/env bb
;; E2E test for weekly shopping list integration.
;;
;; This test:
;; 1. Starts real Mealie container
;; 2. Creates test user via actual API calls
;; 3. Creates an empty shopping list using Mealie v2 API
;; 4. Verifies the list was created with correct name
;;
;; Run with: bb test/e2e_weekly_shopping_test.clj
;;
;; WARNING: Takes ~60 seconds due to service startup times.

(ns lab-test.e2e-weekly-shopping
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Load lab namespace
(load-file "lab")

;; =============================================================================
;; Test Configuration
;; =============================================================================

(def test-project "lab-weekly-shopping-e2e")

(def test-config
  {:services
   {:mealie {:image "ghcr.io/mealie-recipes/mealie:v2.2.0"
             :ports ["19927:9000"]
             :env {:ALLOW_SIGNUP "true"
                   :TZ "UTC"}}}})

(def mealie-url "http://localhost:19927")

;; =============================================================================
;; Mealie API Helpers
;; =============================================================================

(defn mealie-register!
  "Register a user in Mealie v2 (requires group + household + advanced)"
  [base-url]
  (let [resp (lab/curl-post (str base-url "/api/users/register")
                            {"Content-Type" "application/json"}
                            {:email "test@test.com"
                             :username "test"
                             :fullName "Test User"
                             :password "testtest123"
                             :passwordConfirm "testtest123"
                             :group "Home"
                             :household "Default"
                             :advanced true})
        body (or (:body resp) "")]
    (cond
      (str/includes? body "\"id\":")
      (println "  ✓ User registered")

      (str/includes? body "already taken")
      (println "  ✓ User already exists")

      :else
      (throw (ex-info "Mealie registration failed" {:body body})))))

(defn mealie-login!
  "Login to Mealie and return access token"
  [base-url]
  (let [result (shell {:out :string :err :string :continue true}
                      "curl" "-s" "-X" "POST"
                      (str base-url "/api/auth/token")
                      "-H" "Content-Type: application/x-www-form-urlencoded"
                      "-d" "username=test@test.com&password=testtest123")
        body (:out result)]
    (if (str/includes? body "access_token")
      (-> body (json/decode true) :access_token)
      (throw (ex-info "Mealie login failed" {:body body})))))

(defn format-week-name
  "Generate shopping list name in format 'Week of MMM d'"
  []
  (let [today (java.time.LocalDate/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "MMM d")]
    (str "Week of " (.format today fmt))))

(defn create-shopping-list!
  "Create a shopping list via Mealie v2 API. Tries both v2 and v1 endpoints."
  [base-url token list-name]
  (let [headers {"Authorization" (str "Bearer " token)
                 "Content-Type" "application/json"}
        ;; Try v2 path first (households)
        v2-resp (lab/curl-post (str base-url "/api/households/shopping/lists")
                               headers
                               {:name list-name})
        v2-body (:body v2-resp)]
    (if (and v2-body (str/includes? v2-body "\"id\""))
      {:endpoint :households :body v2-body}
      ;; Fall back to v1 path (groups)
      (let [v1-resp (lab/curl-post (str base-url "/api/groups/shopping/lists")
                                   headers
                                   {:name list-name})
            v1-body (:body v1-resp)]
        (if (and v1-body (str/includes? v1-body "\"id\""))
          {:endpoint :groups :body v1-body}
          (throw (ex-info "Failed to create shopping list via both endpoints"
                          {:v2-body v2-body :v1-body v1-body})))))))

(defn get-shopping-lists
  "Get all shopping lists from Mealie. Tries both v2 and v1 endpoints."
  [base-url token]
  (let [headers {"Authorization" (str "Bearer " token)}
        ;; Try v2 path first (households)
        v2-resp (lab/curl-get (str base-url "/api/households/shopping/lists") headers)
        v2-body (:body v2-resp)]
    (if (and v2-body (str/includes? v2-body "\"items\""))
      (-> v2-body (json/decode true) :items)
      ;; Fall back to v1 path (groups)
      (let [v1-resp (lab/curl-get (str base-url "/api/groups/shopping/lists") headers)
            v1-body (:body v1-resp)]
        (when (and v1-body (str/includes? v1-body "\"items\""))
          (-> v1-body (json/decode true) :items))))))

;; =============================================================================
;; E2E Test
;; =============================================================================

(defn docker-available? []
  (try
    (-> (shell {:out :string :err :string :continue true} "docker" "info")
        :exit
        zero?)
    (catch Exception _ false)))

(deftest weekly-shopping-list-e2e-test
  (if-not (docker-available?)
    (println "SKIP: Docker not available")

    (testing "Creates weekly shopping list via Mealie API"
      (let [yaml-str (lab/config->yaml test-config)]

        (try
          ;; 1. Start Mealie
          (println "\n1. Starting Mealie...")
          (shell {:in yaml-str :out :string}
                 "docker" "compose" "-f" "-" "-p" test-project "up" "-d")

          ;; 2. Wait for service
          (println "\n2. Waiting for Mealie to be ready...")
          (Thread/sleep 15000)
          (lab/wait-for-service (str mealie-url "/api/app/about") 30 2000)

          ;; 3. Setup user
          (println "\n3. Setting up Mealie user...")
          (mealie-register! mealie-url)
          (let [token (mealie-login! mealie-url)
                _ (println "  ✓ Logged in to Mealie")

                ;; 4. Create shopping list
                _ (println "\n4. Creating weekly shopping list...")
                list-name (format-week-name)
                _ (println (str "  List name: " list-name))
                create-result (create-shopping-list! mealie-url token list-name)
                _ (println (str "  ✓ Created via /" (name (:endpoint create-result)) " endpoint"))

                ;; 5. Verify list exists
                _ (println "\n5. Verifying shopping list was created...")
                lists (get-shopping-lists mealie-url token)
                matching-list (some #(when (= (:name %) list-name) %) lists)]

            (is (some? matching-list) (str "Should find shopping list named '" list-name "'"))
            (when matching-list
              (println (str "  ✓ Found list: " (:name matching-list) " (id: " (:id matching-list) ")"))))

          (finally
            ;; Cleanup
            (println "\n6. Cleaning up containers...")
            (shell {:in yaml-str :out :string :continue true}
                   "docker" "compose" "-f" "-" "-p" test-project "down" "-v")
            (println "  ✓ Done")))))))

;; =============================================================================
;; Runner
;; =============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (println "\n" (str (apply str (repeat 60 "=")) "\n"))
  (println "WEEKLY SHOPPING LIST E2E TEST")
  (println "Starts real Mealie container")
  (println "Tests shopping list creation via Mealie v2 API")
  (println "Expected runtime: ~60 seconds")
  (println "\n" (str (apply str (repeat 60 "=")) "\n"))
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
