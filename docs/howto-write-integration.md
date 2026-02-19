# How to Write an Integration

This guide shows how to create your own automation that connects services.

## Types of Integrations

Lab supports two types:

1. **HTTP Call** - Simple API request defined in EDN (no code)
2. **Code Handler** - Clojure file for complex logic

## HTTP Call Integration (Simple)

For simple webhooks or single API calls, define everything in config.edn:

```clojure
:integrations
{:my-webhook
 {:type :http-call
  :description "Ping my monitoring service"
  :schedule "*/5 * * * *"  ; Every 5 minutes
  :action
  {:method :post
   :url "https://healthchecks.io/ping/abc123"
   :headers {:Content-Type "application/json"}
   :body {:status "ok"}}}}
```

### Template Values

You can use `:current-date` in the body:
```clojure
:body {:name #join ["Report - " :current-date]}
;; Produces: {"name": "Report - Feb 19, 2026"}
```

## Code Handler Integration (Complex)

For multi-step logic, create a Clojure file.

### Step 1: Create the Handler File

Create `integrations/my-integration.clj`:

```clojure
;; integrations/my-integration.clj
;;
;; Description of what this does

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; Helper functions
(defn my-api-call [url token method path body]
  (let [resp (http/request
              {:method method
               :uri (str url path)
               :headers {"Authorization" (str "Bearer " token)
                         "Content-Type" "application/json"}
               :body (when body (json/encode body))
               :throw false})]
    (when (<= 200 (:status resp) 299)
      (json/decode (:body resp) true))))

;; The handler function - receives full config
;; Must return :ok or :error
(fn [{:keys [secrets]}]
  (let [api-url (get-in secrets [:my-service :url])
        token (get-in secrets [:my-service :token])]
    (println "  Starting my integration...")
    
    ;; Your logic here
    (if (my-api-call api-url token :get "/health" nil)
      (do
        (println "  Success!")
        :ok)
      (do
        (println "  Failed!")
        :error))))
```

### Step 2: Add Secrets

Update `secrets.edn`:
```clojure
{:my-service {:url "https://api.example.com"
              :token "secret-token"}}
```

### Step 3: Register in config.edn

```clojure
:integrations
{:my-integration
 {:handler "integrations/my-integration.clj"
  :description "My custom automation"
  :schedule "0 9 * * *"}}  ; Optional: 9am daily
```

### Step 4: Test It

```bash
./lab run my-integration
```

## Available Helpers

When your handler runs, you have access to:

### From Babashka
```clojure
(require '[babashka.http-client :as http])  ; HTTP requests
(require '[cheshire.core :as json])          ; JSON encode/decode
(require '[clojure.string :as str])          ; String manipulation
(require '[babashka.fs :as fs])              ; File system
```

### Config Structure

Your handler receives the full config:
```clojure
(fn [{:keys [secrets services integrations]}]
  ;; secrets = contents of secrets.edn
  ;; services = service definitions
  ;; integrations = all integrations
  ...)
```

## Best Practices

### 1. Print Progress

Users appreciate visibility:
```clojure
(println "  Fetching data...")
(println (str "  Found " (count items) " items"))
(println "  Done!")
```

### 2. Return Status

Always return `:ok` or `:error`:
```clojure
(if success?
  (do (println "  ✓ Complete") :ok)
  (do (println "  ✗ Failed") :error))
```

### 3. Handle Errors Gracefully

```clojure
(try
  (do-risky-thing)
  (catch Exception e
    (println (str "  ERROR: " (.getMessage e)))
    :error))
```

### 4. Test in Isolation

Create a test file:
```clojure
;; test/my_integration_test.clj
(ns my-integration-test
  (:require [clojure.test :refer :all]))

(def mock-config
  {:secrets {:my-service {:url "http://localhost:8080"
                          :token "test"}}})

(deftest test-handler
  (let [handler (load-file "integrations/my-integration.clj")]
    (is (= :ok (handler mock-config)))))
```

## Real-World Examples

### Cross-Service Integration

See `integrations/meal-prep-reminder.clj`:
- Reads from Mealie (recipes with "prep:" notes)
- Writes to Donetick (creates chores)

### Data Aggregation

See `integrations/mealie.clj`:
- Fetches meal plan
- Extracts ingredients
- Creates shopping list

## Debugging

### Run with Verbose Output

```bash
./lab run my-integration 2>&1 | tee debug.log
```

### Test API Calls Manually

```bash
curl -H "Authorization: Bearer $TOKEN" https://api.example.com/endpoint
```

### Check Config Loading

```bash
./lab plan  # Shows parsed config
```
