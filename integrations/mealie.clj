;; integrations/mealie.clj
;; Complex integration example - creates weekly shopping list with meal planning
;;
;; This handler receives the full config and can do anything:
;; - Multiple API calls
;; - Data transformation
;; - Conditional logic
;; - Error handling

(require '[babashka.http-client :as http]
         '[cheshire.core :as json])

(defn mealie-api
  "Make authenticated Mealie API call"
  [{:keys [url token]} method path & [body]]
  (let [resp (http/request
              {:method method
               :uri (str url "/api" path)
               :headers {"Authorization" (str "Bearer " token)
                         "Content-Type" "application/json"}
               :body (when body (json/encode body))})]
    (when (<= 200 (:status resp) 299)
      (json/decode (:body resp) true))))

(defn get-this-weeks-meals
  "Fetch meal plan for the current week"
  [mealie-config]
  (let [today (java.time.LocalDate/now)
        start (.toString today)
        end (.toString (.plusDays today 7))]
    (mealie-api mealie-config :get (str "/groups/mealplans?start=" start "&end=" end))))

(defn extract-ingredients
  "Extract unique ingredients from meals"
  [meals]
  (->> meals
       (mapcat :recipe_ingredients)
       (map :note)
       (filter some?)
       distinct))

(defn create-shopping-list
  "Create a shopping list with items"
  [mealie-config name items]
  (let [list-resp (mealie-api mealie-config :post "/groups/shopping/lists" {:name name})]
    (when list-resp
      (doseq [item items]
        (mealie-api mealie-config :post
                    (str "/groups/shopping/lists/" (:id list-resp) "/items")
                    {:note item}))
      list-resp)))

(defn format-week-name []
  (let [today (java.time.LocalDate/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "MMM d")]
    (str "Week of " (.format today fmt))))

;; The handler function - receives full config
(fn [{:keys [secrets]}]
  (let [mealie (:mealie secrets)
        _ (println "  Fetching this week's meals...")
        meals (get-this-weeks-meals mealie)]
    (if (seq meals)
      (let [ingredients (extract-ingredients meals)
            _ (println (str "  Found " (count ingredients) " ingredients"))
            list-name (format-week-name)
            result (create-shopping-list mealie list-name ingredients)]
        (if result
          (do (println (str "  Created: " list-name))
              :ok)
          (do (println "  ERROR: Failed to create list")
              :error)))
      ;; No meals planned - just create empty list
      (let [list-name (format-week-name)]
        (println "  No meals planned, creating empty list")
        (if (mealie-api mealie :post "/groups/shopping/lists" {:name list-name})
          (do (println (str "  Created: " list-name)) :ok)
          (do (println "  ERROR: Failed to create list") :error))))))
