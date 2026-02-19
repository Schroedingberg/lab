#!/usr/bin/env bb
;; E2E test for meal prep reminder integration.
;;
;; This test:
;; 1. Starts real Mealie + Donetick containers
;; 2. Creates test users via actual API calls
;; 3. Creates a recipe with prep notes
;; 4. Adds it to tomorrow's meal plan
;; 5. Runs the integration handler
;; 6. Verifies chore was created in Donetick
;;
;; Run with: bb test/e2e_meal_prep_test.clj
;;
;; WARNING: Takes ~90 seconds due to service startup times.

(ns lab-test.e2e-meal-prep
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Load lab namespace
(load-file "lab.bb")

;; =============================================================================
;; Test Configuration
;; =============================================================================

(def test-project "lab-meal-prep-e2e")

(def test-config
  {:services
   {:mealie {:image "ghcr.io/mealie-recipes/mealie:v2.2.0"
             :ports ["19925:9000"]
             :env {:ALLOW_SIGNUP "true"
                   :TZ "UTC"}}
    :donetick {:image "donetick/donetick"
               :ports ["19926:2021"]
               :tmpfs ["/donetick-data"]
               :env {:DT_ENV "selfhosted"
                     :DT_JWT_SECRET "test-e2e-secret-key-32-chars-long!!!"
                     :DT_SQLITE_PATH "/donetick-data/donetick.db"
                     :TZ "UTC"}}}})

(def mealie-url "http://localhost:19925")
(def donetick-url "http://localhost:19926")

;; =============================================================================
;; Mealie API (test setup helpers)
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
      ;; Success (user ID returned)
      (str/includes? body "\"id\":")
      (println "  ✓ User registered")

      ;; Already exists - that's OK
      (str/includes? body "already taken")
      (println "  ✓ User already exists")

      ;; Real error
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

(defn mealie-create-recipe!
  "Create a test recipe, returns the slug"
  [base-url token]
  (let [resp (lab/curl-post (str base-url "/api/recipes")
                            {"Authorization" (str "Bearer " token)
                             "Content-Type" "application/json"}
                            {:name "Test Salmon Dinner"})
        body (str/trim (:body resp))]
    ;; API returns just the slug as a quoted string: "test-salmon-dinner"
    (if (and (str/starts-with? body "\"") (str/ends-with? body "\""))
      (json/decode body)  ;; Returns the slug string
      (throw (ex-info "Failed to create recipe" {:body body})))))

(defn mealie-update-recipe!
  "Update recipe with prep notes"
  [base-url token slug]
  (let [result (shell {:out :string :err :string :continue true}
                      "curl" "-s" "-X" "PATCH"
                      (str base-url "/api/recipes/" slug)
                      "-H" (str "Authorization: Bearer " token)
                      "-H" "Content-Type: application/json"
                      "-d" (json/encode {:notes [{:title "Prep"
                                                  :text "prep:Take salmon out of freezer\nServe with rice."}]}))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to update recipe" {:body (:out result)})))))

(defn mealie-get-recipe-id
  "Get recipe UUID from slug"
  [base-url token slug]
  (let [resp (lab/curl-get (str base-url "/api/recipes/" slug)
                           {"Authorization" (str "Bearer " token)})
        body (:body resp)]
    (when (str/includes? body "\"id\"")
      (-> body (json/decode true) :id))))

(defn mealie-add-to-meal-plan!
  "Add recipe to tomorrow's meal plan"
  [base-url token recipe-id]
  (let [tomorrow (-> (java.time.LocalDate/now)
                     (.plusDays 1)
                     .toString)
        resp (lab/curl-post (str base-url "/api/households/mealplans")
                            {"Authorization" (str "Bearer " token)
                             "Content-Type" "application/json"}
                            {:date tomorrow
                             :entryType "dinner"
                             :recipeId recipe-id})
        body (:body resp)]
    (if (or (str/includes? body "\"id\"") (str/includes? body "\"date\""))
      tomorrow
      (throw (ex-info "Failed to add meal plan" {:body body})))))

;; =============================================================================
;; Donetick API
;; =============================================================================

(defn donetick-register!
  "Register a user in Donetick"
  [base-url]
  (let [resp (lab/curl-post (str base-url "/api/v1/auth/")
                            {"Content-Type" "application/json"}
                            {:username "testuser"
                             :email "test@test.com"
                             :password "testtest123"})
        body (:body resp)]
    ;; 201 Created returns empty body, that's success
    ;; Error would have {"error": ...}
    (when (str/includes? (or body "") "error")
      (throw (ex-info "Donetick registration failed" {:body body})))))

(defn donetick-login!
  "Login to Donetick and return access token"
  [base-url]
  (let [resp (lab/curl-post (str base-url "/api/v1/auth/login")
                            {"Content-Type" "application/json"}
                            {:username "testuser"
                             :password "testtest123"})
        body (:body resp)]
    (if (str/includes? body "access_token")
      (-> body (json/decode true) :access_token)
      (throw (ex-info "Donetick login failed" {:body body})))))

(defn donetick-get-chores
  "Get all chores from Donetick"
  [base-url token]
  (let [resp (lab/curl-get (str base-url "/api/v1/chores/")
                           {"Authorization" (str "Bearer " token)})
        body (:body resp)]
    (when (str/includes? body "\"res\"")
      (-> body (json/decode true) :res))))

;; =============================================================================
;; E2E Test
;; =============================================================================

(defn docker-available? []
  (try
    (-> (shell {:out :string :err :string :continue true} "docker" "info")
        :exit
        zero?)
    (catch Exception _ false)))

(deftest meal-prep-e2e-test
  (if-not (docker-available?)
    (println "SKIP: Docker not available")

    (testing "Full meal prep reminder workflow with real services"
      (let [yaml-str (lab/config->yaml test-config)]

        (try
          ;; 1. Start services
          (println "\n1. Starting Mealie + Donetick...")
          (shell {:in yaml-str :out :string}
                 "docker" "compose" "-f" "-" "-p" test-project "up" "-d")

          ;; 2. Wait for services
          (println "\n2. Waiting for services to be ready...")
          (Thread/sleep 15000)
          (lab/wait-for-service (str mealie-url "/api/app/about") 30 2000)
          ;; Donetick doesn't have a health endpoint, try API root
          (lab/wait-for-service (str donetick-url "/api/v1/auth/") 30 2000)

          ;; 3. Setup Mealie
          (println "\n3. Setting up Mealie user...")
          (mealie-register! mealie-url)
          (let [mealie-token (mealie-login! mealie-url)
                _ (println "  ✓ Logged in to Mealie")

                ;; 4. Setup Donetick
                _ (println "\n4. Setting up Donetick user...")
                _ (donetick-register! donetick-url)
                donetick-token (donetick-login! donetick-url)
                _ (println "  ✓ Logged in to Donetick")

                ;; 5. Create recipe with prep note
                _ (println "\n5. Creating recipe with prep note...")
                slug (mealie-create-recipe! mealie-url mealie-token)
                _ (mealie-update-recipe! mealie-url mealie-token slug)
                recipe-id (mealie-get-recipe-id mealie-url mealie-token slug)
                _ (println (str "  ✓ Created recipe: " slug " (id: " recipe-id ")"))

                ;; 6. Add to meal plan
                _ (println "\n6. Adding to tomorrow's meal plan...")
                meal-date (mealie-add-to-meal-plan! mealie-url mealie-token recipe-id)
                _ (println (str "  ✓ Added to meal plan for " meal-date))

                ;; 7. Run the integration handler
                _ (println "\n7. Running meal-prep-reminder handler...")
                handler (load-file "integrations/meal-prep-reminder.clj")
                test-secrets {:mealie {:url mealie-url :token mealie-token}
                              :donetick {:url donetick-url :token donetick-token}}
                result (handler {:secrets test-secrets})]

            (is (= :ok result) "Integration should return :ok")

            ;; 8. Verify chore created
            (println "\n8. Verifying chore was created in Donetick...")
            (let [chores (donetick-get-chores donetick-url donetick-token)
                  salmon-chore (some #(when (str/includes? (str/lower-case (:name %)) "salmon") %) chores)]
              (is (some? salmon-chore) "Should find a chore mentioning salmon")
              (when salmon-chore
                (println (str "  ✓ Found chore: " (:name salmon-chore))))))

          (finally
            ;; Cleanup
            (println "\n9. Cleaning up containers...")
            (shell {:in yaml-str :out :string :continue true}
                   "docker" "compose" "-f" "-" "-p" test-project "down" "-v")
            (println "  ✓ Done")))))))

;; =============================================================================
;; Runner
;; =============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (println "\n" (str (apply str (repeat 60 "=")) "\n"))
  (println "MEAL PREP INTEGRATION E2E TEST")
  (println "Starts real Mealie + Donetick containers")
  (println "Makes actual API calls to test the full workflow")
  (println "Expected runtime: ~90 seconds")
  (println "\n" (str (apply str (repeat 60 "=")) "\n"))
  (let [{:keys [fail error]} (run-tests)]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
