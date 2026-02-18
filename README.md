# lab-orchestrator v2

Simple homelab infrastructure management. ~250 lines of Babashka.

## Philosophy

- **EDN is the source of truth** - services and integrations in one config
- **Don't reinvent Docker Compose** - generate YAML, pipe to `docker compose -f -`
- **No temp files** - YAML is ephemeral, piped via stdin, never written to disk
- **Integrations are the value** - automations between services

## Quick Start

```bash
# 1. Install Babashka (if needed)
# macOS: brew install borkdude/brew/babashka
# Linux: curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && bash install

# 2. Setup secrets
cp secrets.edn.example secrets.edn
vim secrets.edn

# 3. Run
./lab plan     # See generated docker-compose.yml
./lab up       # Start services
./lab status   # Check what's running
./lab down     # Stop everything
```

## Current Setup

**Services:**
- **Mealie** - Recipe management and meal planning
- **Donetick** - Chore/task tracking

**Integrations:**
- `meal-prep-reminder` - Checks tomorrow's meals for prep notes (e.g., "prep:defrost salmon"), creates chores in Donetick
- `mealie-weekly-list` - Creates weekly shopping list from meal plan

## Configuration

Everything lives in `config.edn`:

```clojure
{:secrets #include "secrets.edn"

 ;; Services → docker-compose.yml
 :services
 {:mealie {:image "ghcr.io/mealie-recipes/mealie:v3.10.2"
           :ports ["9925:9000"]
           :env {:BASE_URL #ref [:secrets :mealie-url]}}

  :donetick {:image "donetick/donetick"
             :ports ["2021:2021"]
             :env {:DT_JWT_SECRET #ref [:secrets :donetick :jwt-secret]}}}

 ;; Integrations → automations
 :integrations
 {:meal-prep-reminder
  {:handler "integrations/meal-prep-reminder.clj"
   :description "Cross-service: Mealie -> Donetick"}}}
```

## Commands

| Command | Description |
|---------|-------------|
| `./lab up` | Start services (generates YAML on the fly) |
| `./lab down` | Stop all services |
| `./lab status` | Show running services |
| `./lab plan` | Preview the generated YAML (not written to disk) |
| `./lab logs [service]` | Follow logs |
| `./lab run <integration>` | Run an integration |
| `./lab integrations` | List available integrations |

## Integrations

Two types of integrations:

1. **HTTP calls** - Simple webhooks/API requests defined in EDN
2. **Code handlers** - Complex logic in Clojure files

Run manually:
```bash
./lab run meal-prep-reminder
```

Or schedule via cron:
```bash
crontab -e
# Add: 0 8 * * * /opt/lab/lab run meal-prep-reminder
```

## Testing

```bash
./test.sh unit         # Fast unit tests (~1s)
./test.sh integration  # Integration tests with mocks
./test.sh e2e          # Real containers - Mealie + Donetick (~90s)
./test.sh all          # Everything
```

## Deployment

```bash
# On your VPS/homelab server:

# 1. Install Babashka
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
sudo bash install

# 2. Clone/rsync your config
rsync -avz --exclude secrets.edn ./ user@server:/opt/lab/

# 3. On server: setup secrets and run
cd /opt/lab
cp secrets.edn.example secrets.edn
vim secrets.edn
./lab up
```

## Adding Services

Edit `config.edn`:

```clojure
:services
{:new-service
 {:image "nginx:latest"
  :ports ["80:80"]
  :volumes ["./html:/usr/share/nginx/html:ro"]
  :restart "unless-stopped"}}
```

Then: `./lab up`

## Adding Integrations

**Simple HTTP call:**
```clojure
:integrations
{:notify-on-backup
 {:type :http-call
  :action {:method :post
           :url #ref [:secrets :webhook-url]
           :body {:text "Backup completed"}}}}
```

**Code handler (for complex logic):**
```clojure
:integrations
{:meal-prep-reminder
 {:handler "integrations/meal-prep-reminder.clj"
  :description "Check meal plan, create prep chores"}}
```

Handler files receive config and return `:ok` or `:error`:
```clojure
;; integrations/my-handler.clj
(fn [{:keys [secrets]}]
  ;; your logic here
  :ok)
```

Run: `./lab run meal-prep-reminder`

## Config Reference

### Service Options

| Key | Description |
|-----|-------------|
| `:image` | Docker image |
| `:ports` | Port mappings `["host:container"]` |
| `:volumes` | Volume mounts |
| `:env` | Environment variables (map) |
| `:restart` | Restart policy |
| `:labels` | Docker labels |
| `:depends-on` | Service dependencies |
| `:networks` | Networks to join |

### EDN Tags

| Tag | Description |
|-----|-------------|
| `#include "file"` | Include another EDN file |
| `#ref [:path :to :value]` | Reference another config value |
| `#env KEY` | Read environment variable |
| `#join [parts...]` | Concatenate strings |

## vs. v1 (Polylith version)

| Aspect | v1 | v2 |
|--------|----|----|
| Lines of code | ~1,700 | ~300 (core) |
| Startup time | ~3s (JVM) | ~30ms |
| Docker API | Direct via contajners | Via docker compose |
| Structure | 6 Polylith components | 1 script + integrations |
| Build step | Required | None |

The tradeoff: v2 delegates reconciliation to Docker Compose. You lose the custom `plan` diff, but gain simplicity and battle-tested container management.
