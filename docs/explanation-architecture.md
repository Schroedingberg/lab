# Explanation: Architecture

This document explains the design decisions behind lab-orchestrator.

## Why Babashka?

[Babashka](https://babashka.org/) is a fast-starting Clojure interpreter.

### Instant Startup

Traditional Clojure (JVM) takes 2-5 seconds to start. Babashka starts in ~50ms.

For a CLI tool that might run via cron every few minutes, startup time matters:
```bash
time bb -e '(println "hello")'
# real    0m0.050s
```

### Single-File Distribution

The entire orchestrator is ~300 lines in one file (`lab.bb`). No compilation, no JAR files, no dependency resolution at runtime.

```bash
./lab up  # Just works
```

### Built-in Batteries

Babashka includes:
- HTTP client (`babashka.http-client`)
- JSON (`cheshire.core`)
- YAML (`clj-yaml.core`)
- File system (`babashka.fs`)
- Shell commands (`babashka.process`)

No external dependencies needed.

## Why EDN?

EDN (Extensible Data Notation) is Clojure's data format.

### Data Over Configuration Languages

YAML/JSON are data formats pretending to be configuration. EDN is data that *is* configuration:

```clojure
;; EDN: Real data structures
{:services {:mealie {:image "foo" :ports ["9925:9000"]}}}

;; vs YAML: String parsing
services:
  mealie:
    image: foo
    ports:
      - "9925:9000"
```

### Composability

EDN supports reader tags for extension:
```clojure
:secrets #include "secrets.edn"           ; Include files
:url #ref [:secrets :mealie :url]         ; Reference paths
:header #join ["Bearer " #ref [:token]]   ; String interpolation
:key #env API_KEY                         ; Environment variables
```

YAML's `!include` etc. are non-standard and executor-dependent.

### Comments

EDN has real comments:
```clojure
{:services
 {:mealie
  {:image "mealie:v2"  ; Comment explaining version choice
   ;; This whole block is commented
   ;; :debug {:env {:LOG_LEVEL "debug"}}
   }}}
```

## Why Pipe to Docker Compose?

### Don't Reinvent

Docker Compose already handles:
- Container lifecycle
- Network creation
- Volume management
- Dependency ordering
- Health checks
- Restart policies

Lab just generates the YAML — Docker Compose does the work.

### No Temp Files

YAML is piped via stdin:
```bash
echo "$yaml" | docker compose -f - up -d
```

Why avoid files?
1. **No stale state** — YAML is always freshly generated
2. **No permission issues** — Nothing to clean up
3. **Single source of truth** — `config.edn` is THE config

### Escape Hatch

Need raw Docker Compose features? Use `docker compose` directly:
```bash
./lab plan > /tmp/compose.yml
docker compose -f /tmp/compose.yml config  # Validate
```

## Two Types of Integrations

### HTTP Call (Simple)

For webhooks and simple API calls:
```clojure
{:type :http-call
 :action {:method :post :url "..." :body {...}}}
```

No code needed. Defined entirely in EDN.

**Use when:**
- Single API request
- No conditional logic
- No data transformation

### Code Handler (Complex)

For multi-step automations:
```clojure
{:handler "integrations/my-handler.clj"}
```

**Use when:**
- Multiple API calls
- Cross-service coordination
- Conditional logic
- Data transformation

### Why Not Just Code?

HTTP call integrations are:
1. **Visible** — Config shows exactly what happens
2. **Safe** — No arbitrary code execution
3. **Simple** — No Clojure knowledge needed

## Side Effects at the Edges

The codebase follows functional design:

```
┌─────────────────────────────────────────┐
│              Pure Core                  │
│  load-config, config->yaml, resolve-*  │
│  No I/O, no side effects               │
└─────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────┐
│          Side Effect Shell             │
│  compose!, cmd-up, cmd-run, curl-*     │
│  Docker, HTTP, printing                │
└─────────────────────────────────────────┘
```

Benefits:
- Pure functions are easy to test
- Side effects are isolated and visible
- Easier to reason about behavior

## Tradeoffs

### vs v1 (Polylith Architecture)

v1 used Polylith for modular components. v2 is a single script.

| Aspect | v1 Polylith | v2 Single Script |
|--------|-------------|------------------|
| Startup | ~2s (JVM) | ~50ms |
| Complexity | Multiple files, namespaces | One file |
| Testing | Full test infrastructure | bb-based tests |
| IDE support | Full Clojure | Basic |
| Extensibility | Add components | Edit file |

**v2 optimizes for:** simplicity, startup time, deployment ease.

### vs Ansible/Terraform

Lab is NOT trying to be:
- A configuration management tool (Ansible)
- Infrastructure as code (Terraform)
- A container orchestrator (Kubernetes)

Lab is:
- Docker Compose with EDN config
- Integration/automation runner
- Personal homelab tool

### vs n8n/Huginn

Automation platforms like n8n have:
- Visual workflow builders
- Hundreds of integrations
- Web UIs

Lab has:
- Code as configuration
- You write the integrations
- CLI only

**Lab wins when:** you want version control, simple deployment, Clojure.

## Future Architecture

Potential additions (see copilot-instructions.md):

1. **`./lab schedule`** — Generate crontab entries (done!)
2. **`./lab watch`** — Long-running poller for event detection
3. **Webhook receiver** — HTTP server for service callbacks

All implementable in Babashka. No JVM needed.
