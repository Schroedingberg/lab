;; integrations/meal-prep-reminder.clj
;; 
;; When tomorrow's meals have prep requirements (e.g. "defrost fish"),
;; create chore items for today in Donetick.
;;
;; Recipe format: Add a note starting with "prep:" to indicate prep needed
;; Example: "prep:Take fish out of freezer"
;;
;; Run daily via cron: 0 8 * * * /opt/lab/lab run meal-prep-reminder

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; =============================================================================
;; API Helpers
;; =============================================================================

(defn mealie-api
  "Make authenticated Mealie API call"
  [{:keys [url token]} method path & [opts]]
  (let [resp (http/request
              (merge
               {:method method
                :uri (str url "/api" path)
                :headers {"Authorization" (str "Bearer " token)
                          "Content-Type" "application/json"
                          "Accept" "application/json"}}
               opts))]
    (when (<= 200 (:status resp) 299)
      (when (seq (:body resp))
        (json/decode (:body resp) true)))))

(defn donetick-api
  "Make authenticated Donetick API call"
  [{:keys [url token]} method path & [opts]]
  (let [resp (http/request
              (merge
               {:method method
                :uri (str url "/api/v1" path)
                :headers {"Authorization" (str "Bearer " token)
                          "Content-Type" "application/json"}
                :throw false}
               opts))]
    (when (<= 200 (:status resp) 299)
      (when (seq (:body resp))
        (json/decode (:body resp) true)))))

;; =============================================================================
;; Mealie Functions
;; =============================================================================

(defn get-meal-plan
  "Get meal plan for a specific date range"
  [mealie-config start-date end-date]
  ;; Mealie v2 returns paginated response: {:items [...], :page, :total}
  (let [response (mealie-api mealie-config :get
                             (str "/households/mealplans?start_date=" start-date "&end_date=" end-date))]
    ;; Return the items array, or the response if it's already an array (v1 compat)
    (if (map? response)
      (:items response)
      response)))

(defn get-recipe
  "Get full recipe details by slug or ID"
  [mealie-config recipe-id]
  (mealie-api mealie-config :get (str "/recipes/" recipe-id)))

(defn extract-prep-notes
  "Extract prep: notes from recipe.
   Looks in recipe notes and extras for lines starting with 'prep:'"
  [recipe]
  (let [;; Mealie v2: notes is an array of {:title, :text} objects
        notes-text (if (sequential? (:notes recipe))
                     (->> (:notes recipe)
                          (map :text)
                          (filter string?)
                          (str/join "\n"))
                     (or (:notes recipe) ""))
        ;; Check extras (custom fields)
        extras-notes (->> (:extras recipe)
                          (map :value)
                          (filter string?)
                          (str/join "\n"))
        all-text (str notes-text "\n" extras-notes)]
    (->> (str/split-lines all-text)
         (map str/trim)
         (filter #(str/starts-with? (str/lower-case %) "prep:"))
         (map #(str/trim (subs % 5)))  ; Remove "prep:" prefix
         (filter seq))))

;; =============================================================================
;; Donetick Functions  
;; =============================================================================

(defn get-chores
  "Get all chores from Donetick"
  [donetick-config]
  (-> (donetick-api donetick-config :get "/chores/")
      :res))

(defn create-chore
  "Create a chore in Donetick"
  [donetick-config title]
  (donetick-api donetick-config :post "/chores/"
                {:body (json/encode {:name title
                                     :frequencyType "once"
                                     :assignStrategy "random"
                                     :isActive true})}))

(defn chore-exists?
  "Check if a chore with this name already exists"
  [donetick-config title]
  (let [chores (get-chores donetick-config)]
    (some #(= title (:name %)) chores)))

;; =============================================================================
;; Integration Logic
;; =============================================================================

(defn tomorrow []
  (-> (java.time.LocalDate/now)
      (.plusDays 1)
      .toString))

(defn today []
  (.toString (java.time.LocalDate/now)))

(defn process-meal-prep!
  "Check tomorrow's meals for prep requirements, create chores for today"
  [{:keys [mealie donetick]} opts]
  (println "  Checking tomorrow's meals for prep requirements...")

  (let [tomorrow-date (tomorrow)

        ;; Get tomorrow's meal plan
        meal-plan (get-meal-plan mealie tomorrow-date tomorrow-date)
        _ (println (str "  Found " (count meal-plan) " meals for " tomorrow-date))

        ;; Process each meal
        created-chores (atom [])]

    (doseq [meal meal-plan]
      (let [recipe-slug (or (get-in meal [:recipe :slug])
                            (get-in meal [:recipeId]))
            recipe (when recipe-slug (get-recipe mealie recipe-slug))
            prep-notes (when recipe (extract-prep-notes recipe))]

        (when (seq prep-notes)
          (println (str "  Recipe '" (:name recipe) "' has prep: " (count prep-notes) " items"))

          (doseq [prep-note prep-notes]
            (let [chore-name (str "🍳 " prep-note " (for: " (:name recipe) ")")]
              (if (chore-exists? donetick chore-name)
                (println (str "    Skip (exists): " prep-note))
                (do
                  (create-chore donetick chore-name)
                  (println (str "    Created: " prep-note))
                  (swap! created-chores conj chore-name))))))))

    (if (seq @created-chores)
      (do
        (println (str "\n  Created " (count @created-chores) " prep reminder(s)"))
        :ok)
      (do
        (println "  No prep reminders needed")
        :ok))))

;; =============================================================================
;; Handler (entry point)
;; =============================================================================

(fn [{:keys [secrets]}]
  (process-meal-prep!
   {:mealie (:mealie secrets)
    :donetick (:donetick secrets)}
   {}))
