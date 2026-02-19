# Reference: Configuration (config.edn)

Complete reference for the `config.edn` schema.

## Top-Level Structure

```clojure
{:secrets   <map or #include>
 :services  <map of service definitions>
 :integrations <map of integration definitions>}
```

## EDN Reader Tags

Custom reader tags for composing configuration:

### `#include`

Include another EDN file:
```clojure
:secrets #include "secrets.edn"
```

Path is relative to the config file location.

### `#ref`

Reference another path in the config:
```clojure
:mealie-url #ref [:secrets :mealie :url]
```

The path is a vector of keys to traverse.

### `#env`

Read environment variable:
```clojure
:api-key #env API_KEY
```

Returns `nil` if not set.

### `#join`

Concatenate strings and references:
```clojure
:auth-header #join ["Bearer " #ref [:secrets :token]]
;; Produces: "Bearer abc123"
```

Can also use `:current-date` placeholder in HTTP bodies:
```clojure
:body {:name #join ["Week of " :current-date]}
```

## Services

Services become Docker Compose service definitions.

```clojure
:services
{:service-name
 {:image       <string>      ; Required: Docker image
  :ports       [<strings>]   ; Optional: Port mappings
  :volumes     [<strings>]   ; Optional: Volume mounts
  :tmpfs       [<strings>]   ; Optional: tmpfs mounts
  :env         <map>         ; Optional: Environment variables
  :restart     <string>      ; Optional: Restart policy
  :labels      <map>         ; Optional: Container labels
  :depends-on  [<keywords>]  ; Optional: Service dependencies
  :networks    [<strings>]}} ; Optional: Networks
```

### Service Example

```clojure
:services
{:mealie
 {:image "ghcr.io/mealie-recipes/mealie:v3.10.2"
  :ports ["9925:9000"]
  :volumes ["mealie-data:/app/data"]
  :env {:ALLOW_SIGNUP "false"
        :PUID "1000"
        :PGID "1000"
        :TZ "Europe/Berlin"
        :BASE_URL #ref [:secrets :mealie-url]}
  :restart "unless-stopped"}}
```

### Generated Compose Output

The above produces:
```yaml
services:
  mealie:
    image: ghcr.io/mealie-recipes/mealie:v3.10.2
    ports:
      - "9925:9000"
    volumes:
      - mealie-data:/app/data
    environment:
      ALLOW_SIGNUP: "false"
      PUID: "1000"
      PGID: "1000"
      TZ: Europe/Berlin
      BASE_URL: https://mealie.example.com
    restart: unless-stopped
```

## Integrations

Two types: HTTP call or code handler.

### HTTP Call Integration

Simple API requests defined entirely in EDN:

```clojure
:integrations
{:my-webhook
 {:type        :http-call    ; Required: identifies type
  :description <string>      ; Optional: human description
  :schedule    <cron-string> ; Optional: cron schedule
  :action
  {:method  <keyword>        ; :get, :post, :put, :delete
   :url     <string>         ; Full URL
   :headers <map>            ; HTTP headers
   :body    <map>}}}         ; JSON body (optional)
```

#### HTTP Call Example

```clojure
:integrations
{:ping-healthcheck
 {:type :http-call
  :description "Ping monitoring service"
  :schedule "*/5 * * * *"
  :action
  {:method :get
   :url "https://hc-ping.com/abc123"}}}
```

### Code Handler Integration

Complex logic in Clojure files:

```clojure
:integrations
{:my-automation
 {:handler     <path>        ; Required: path to .clj file
  :description <string>      ; Optional: human description
  :schedule    <cron-string>}} ; Optional: cron schedule
```

#### Code Handler Example

```clojure
:integrations
{:meal-prep-reminder
 {:handler "integrations/meal-prep-reminder.clj"
  :description "Create prep reminders for tomorrow's meals"
  :schedule "0 8 * * *"}}
```

### Schedule Field

Cron syntax for automatic scheduling:

```
┌───────────── minute (0-59)
│ ┌───────────── hour (0-23)
│ │ ┌───────────── day of month (1-31)
│ │ │ ┌───────────── month (1-12)
│ │ │ │ ┌───────────── day of week (0-6, Sunday=0)
│ │ │ │ │
* * * * *
```

Examples:
| Schedule | Meaning |
|----------|---------|
| `0 8 * * *` | Daily at 8:00 AM |
| `0 23 * * 5` | Fridays at 11:00 PM |
| `*/15 * * * *` | Every 15 minutes |
| `0 9 * * 1-5` | Weekdays at 9:00 AM |
| `0 0 1 * *` | Monthly on the 1st |

## Secrets File

The `secrets.edn` file (gitignored) contains credentials:

```clojure
{;; Flat keys for simple HTTP integrations
 :mealie-url "https://mealie.example.com"
 :mealie-token "eyJ..."
 
 ;; Nested maps for code handlers
 :mealie {:url "https://mealie.example.com"
          :token "eyJ..."}
 
 :donetick {:url "https://donetick.example.com"
            :token "abc123"
            :jwt-secret "for-docker-env"}}
```

## Complete Example

```clojure
{:secrets #include "secrets.edn"

 :services
 {:mealie
  {:image "ghcr.io/mealie-recipes/mealie:v3.10.2"
   :ports ["9925:9000"]
   :volumes ["mealie-data:/app/data"]
   :env {:TZ "Europe/Berlin"
         :BASE_URL #ref [:secrets :mealie-url]}
   :restart "unless-stopped"}

  :donetick
  {:image "donetick/donetick"
   :ports ["2021:2021"]
   :volumes ["donetick-data:/donetick-data"]
   :env {:DT_ENV "selfhosted"
         :DT_JWT_SECRET #ref [:secrets :donetick :jwt-secret]}
   :restart "unless-stopped"}}

 :integrations
 {:mealie-weekly-list
  {:handler "integrations/mealie.clj"
   :description "Create shopping list"
   :schedule "0 23 * * 5"}

  :meal-prep-reminder
  {:handler "integrations/meal-prep-reminder.clj"
   :description "Mealie -> Donetick prep chores"
   :schedule "0 8 * * *"}

  :heartbeat
  {:type :http-call
   :schedule "*/5 * * * *"
   :action {:method :get
            :url "https://hc-ping.com/uuid"}}}}
```
