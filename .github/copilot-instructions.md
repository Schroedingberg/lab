# Lab Orchestrator v2 - Copilot Instructions

## Current State

A ~300 LOC Babashka-based homelab orchestrator that:
- Converts EDN config → Docker Compose YAML (piped via stdin, no temp files)
- Runs integrations (automations) between services
- Currently manages: Mealie (recipes), Donetick (chores)

### File Structure

```
v2/
├── lab                          # Main script (~295 LOC)
├── config.edn                   # Service + integration definitions
├── secrets.edn                  # Credentials (gitignored)
├── integrations/
│   ├── meal-prep-reminder.clj   # Mealie → Donetick (prep notes → chores)
│   └── mealie.clj               # Weekly shopping list from meal plan
└── test/
    ├── unit_test.clj            # Pure function tests
    ├── integration_test.clj     # Mock-based tests
    ├── meal_prep_handler_test.clj
    ├── e2e_test.clj             # Real Mealie container
    └── e2e_meal_prep_test.clj   # Real Mealie + Donetick containers
```

---

## Planned Work

### 1. E2E Test for Weekly Shopping List (`mealie.clj`)

**Problem:** The `mealie.clj` integration (create empty weekly shopping list) has never been E2E tested. User couldn't get it working manually.

**Task:** Create `test/e2e_weekly_shopping_test.clj` that:
1. Starts real Mealie container
2. Creates test user, logs in
3. Runs `mealie.clj` handler (or simplified version)
4. Verifies an empty shopping list was created with correct name ("Week of Feb 18" etc.)

**Implementation notes:**
- Reuse `lab/curl-post`, `lab/curl-get`, `lab/wait-for-service` from `lab`
- Mealie v2 API endpoints:
  - Shopping lists: `POST /api/groups/shopping/lists` (may need v2 path check - could be `/api/households/...`)
  - Get lists: `GET /api/groups/shopping/lists`
- Note: `mealie.clj` uses `/api/groups/...` paths which may be v1 - verify against Mealie v2.2.0

**Acceptance criteria:**
- `./test.sh e2e` passes including new test
- Empty shopping list with "Week of <date>" name is created

---

### 2. Automation Architecture: Documentation & Roadmap

#### 2A. Document MVP Clearly (Divio System)

The current tool works but lacks clear documentation. Create docs following [Divio's documentation system](https://docs.divio.com/documentation-system/):

**Tutorials** (learning-oriented, "follow along"):
- `docs/tutorial-first-integration.md` - Set up from scratch, run your first integration
  - Install Babashka
  - Clone repo, set up secrets
  - Start Mealie container
  - Run `./lab run mealie-weekly-list`
  - See the shopping list appear

**How-to Guides** (task-oriented, "solve a problem"):
- `docs/howto-run-on-schedule.md` - Set up cron or systemd timer
  - Cron example with full paths
  - Systemd timer + service unit files
  - Troubleshooting: PATH issues, secrets not found, logs
- `docs/howto-write-integration.md` - Create your own handler
  - Template file structure
  - Available helpers (`lab/curl-post`, etc.)
  - Testing your integration
- `docs/howto-deploy.md` - Deploy to VPS
  - rsync strategy
  - Secret management (don't commit secrets.edn)
  - Monitoring (check if it ran)

**Reference** (information-oriented, "dry description"):
- `docs/reference-config.md` - Full config.edn schema
  - All `:services` keys
  - All `:integrations` keys  
  - EDN reader tags (#include, #ref, #env, #join)
- `docs/reference-cli.md` - All commands and options

**Explanation** (understanding-oriented, "why"):
- `docs/explanation-architecture.md` - Why this design
  - Why Babashka (startup time, single file)
  - Why pipe to docker-compose (don't reinvent)
  - Why EDN (data > YAML, composable)
  - Tradeoffs vs v1 Polylith approach

**Acceptance criteria:**
- At minimum: one how-to for cron/systemd, one reference for config
- README links to docs/ folder

---

#### 2B. Event-Driven Architecture Roadmap

**Current state (MVP):**
```
┌─────────────────────────────────────────────────────────────┐
│                    TRIGGER LAYER                            │
├─────────────────────────────────────────────────────────────┤
│  Manual     │  ./lab run meal-prep-reminder                 │
│  Scheduled  │  External cron/systemd calls ./lab run ...    │
│  Events     │  ❌ Not supported                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  INTEGRATION HANDLER                        │
│  (integrations/*.clj)                                       │
│  Input:  {:secrets {...}}                                   │
│  Output: :ok | :error                                       │
│  Can:    Make API calls, transform data, conditional logic │
└─────────────────────────────────────────────────────────────┘
```

**Problem:** No way to react to events ("recipe added to plan" → trigger action).

---

**Phase 1: Built-in Scheduler (`./lab serve`)**

Replace external cron with built-in scheduler. Simpler deployment, single process.

```
┌─────────────────────────────────────────────────────────────┐
│                    ./lab serve                              │
├─────────────────────────────────────────────────────────────┤
│  1. Load config.edn                                         │
│  2. Start chime scheduler for each integration with         │
│     :schedule field (cron syntax)                           │
│  3. Block on signal, graceful shutdown                      │
└─────────────────────────────────────────────────────────────┘
```

Config example:
```clojure
:integrations
{:meal-prep-reminder
 {:handler "integrations/meal-prep-reminder.clj"
  :schedule "0 8 * * *"}}   ; <-- chime reads this
```

**Implementation:**
- Use `chime.core` (already in bb.edn)
- Parse cron expressions → chime schedule
- Run handler at scheduled times
- Structured logging (mulog or println)

**Acceptance criteria:**
- `./lab serve` runs continuously
- Handlers execute at scheduled times
- SIGTERM/SIGINT graceful shutdown
- Systemd unit file in docs

---

**Phase 2: Polling-based Events**

Detect changes by polling service APIs. Good for services without webhooks.

```
┌─────────────────────────────────────────────────────────────┐
│                    ./lab serve                              │
├─────────────────────────────────────────────────────────────┤
│  Scheduler:  time-based triggers (Phase 1)                  │
│  Poller:     check services every N minutes                 │
│              detect changes, trigger handlers               │
└─────────────────────────────────────────────────────────────┘
```

Config example:
```clojure
:integrations
{:on-new-meal-plan
 {:handler "integrations/on-new-meal.clj"
  :trigger {:type :poll
            :source :mealie
            :endpoint "/api/households/mealplans"
            :interval-minutes 5
            :on :new-item}}}
```

**Implementation:**
- Poller stores last-seen state (in-memory or file)
- Compares current vs last
- If changed, calls handler with `{:event {:type :new-item :data ...}}`
- Need state file or atom for persistence across restarts

**Questions to resolve:**
- Where to store state? `~/.lab/state.edn`?
- How to define "new item"? By ID? By timestamp?
- Rate limiting for polling?

---

**Phase 3: Webhook Receiver (Optional)**

For services that support webhooks (Mealie does via "Notifiers").

```
┌─────────────────────────────────────────────────────────────┐
│                    ./lab serve                              │
├─────────────────────────────────────────────────────────────┤
│  Scheduler:  time-based triggers                            │
│  Poller:     polling-based events                           │
│  HTTP:       POST /webhook/:integration-name                │
│              receives payload, calls handler                │
└─────────────────────────────────────────────────────────────┘
```

Config example:
```clojure
:integrations
{:on-recipe-created
 {:handler "integrations/on-recipe.clj"
  :trigger {:type :webhook
            :path "/webhook/on-recipe-created"}}}
```

**Implementation:**
- HTTP server (http-kit or ring+jetty, check bb support)
- Route: POST /webhook/:name → find integration → call handler
- Handler receives `{:event {:type :webhook :payload <body>}}`
- Security: shared secret header? IP allowlist?

**Mealie webhook setup:**
- Admin → Settings → Notifiers → Add
- URL: `http://lab-server:8765/webhook/on-recipe-created`

---

**Architecture Decision: Can We Do This in Babashka?**

| Requirement | Babashka Support | Notes |
|-------------|------------------|-------|
| Chime scheduler | ✅ Yes | `chime.core` works in bb |
| HTTP server | ✅ Yes | `org.httpkit.server` or ring |
| Background threads | ✅ Yes | `future`, `core.async` limited |
| File-based state | ✅ Yes | EDN read/write |
| Signal handling | ✅ Yes | `sun.misc.Signal` or shutdown hook |

**Conclusion:** All phases are implementable in Babashka. No need for JVM.

---

**Recommended Roadmap:**

| Phase | Effort | Value | Priority |
|-------|--------|-------|----------|
| 2A: Documentation | Low | High | **Do first** |
| Phase 1: `./lab serve` + scheduler | Medium | High | **Do second** |
| Phase 2: Polling | Medium | Medium | Nice to have |
| Phase 3: Webhooks | High | Medium | Future |

**Acceptance criteria for Phase 1:**
- `./lab serve` command exists
- Reads `:schedule` from config, runs handlers on time
- Stays running until SIGTERM
- Systemd unit file provided
- E2E test: start serve, wait for scheduled run, verify

---

## Helper Code Available in `lab`

When writing tests or integrations, these functions are available after `(load-file "lab")`:

```clojure
;; HTTP helpers (curl-based, works around bb.http-client quirks)
(lab/curl-post url headers body-map)  ; → {:status :body}
(lab/curl-get url headers)            ; → {:status :body}

;; Service readiness
(lab/wait-for-service url max-attempts delay-ms)

;; Config
(lab/load-config)                     ; → parsed config.edn
(lab/config->yaml config)             ; → docker-compose YAML string
```

---

## Testing Commands

```bash
./test.sh unit         # Fast, no containers (~1s)
./test.sh integration  # Mocked APIs (~2s)
./test.sh e2e          # Real containers (~90s)
./test.sh all          # Everything
```

---

## Code Style

- Clojure/Babashka, no JVM dependencies
- Functions over macros
- Data in, data out (pure where possible)
- Side effects at edges (API calls, printing)
- Generous use of `println` for visibility during runs
