# Reference: CLI Commands

Complete reference for all `lab` commands.

## Usage

```bash
./lab <command> [arguments]
```

## Service Commands

Commands that manage Docker Compose services.

### `up`

Start all services defined in config.

```bash
./lab up
```

- Generates Docker Compose YAML from config.edn
- Pipes to `docker compose -f - up -d`
- Services start in detached mode

### `down`

Stop all services.

```bash
./lab down
```

- Runs `docker compose down`
- Containers are stopped and removed
- Volumes are preserved

### `status`

Show running services.

```bash
./lab status
```

- Runs `docker compose ps`
- Shows container state, ports, health

### `plan`

Preview generated Docker Compose YAML.

```bash
./lab plan
```

- Parses config.edn
- Outputs YAML that would be used
- Does not start anything
- Useful for debugging configuration

Example output:
```
Generated docker-compose.yml (ephemeral - never written to disk):
---
services:
  mealie:
    image: ghcr.io/mealie-recipes/mealie:v3.10.2
    ports:
    - "9925:9000"
...
---

Run './lab up' to apply.
```

### `logs`

Follow service logs.

```bash
./lab logs [service]
```

Arguments:
- `service` (optional): Specific service name

Examples:
```bash
./lab logs           # All services
./lab logs mealie    # Only mealie
```

## Integration Commands

Commands for running automations.

### `run`

Execute an integration.

```bash
./lab run <name>
```

Arguments:
- `name` (required): Integration name from config

Exit codes:
- `0`: Success (`:ok` returned)
- `1`: Error (`:error` returned or not found)

Examples:
```bash
./lab run meal-prep-reminder
./lab run mealie-weekly-list
```

Output:
```
Running integration: meal-prep-reminder
  Loading: integrations/meal-prep-reminder.clj
  Fetching tomorrow's meals...
  Found 2 recipes with prep notes
  Created 2 chores
```

### `integrations`

List available integrations.

```bash
./lab integrations
```

Output:
```
Available integrations:
  mealie-weekly-list - code (integrations/mealie.clj) [cron: 0 23 * * 5]
  meal-prep-reminder - code (integrations/meal-prep-reminder.clj) [cron: 0 8 * * *]
  heartbeat - http-call [cron: */5 * * * *]
```

## Schedule Commands

Commands for managing crontab entries.

### `schedule show`

Preview crontab entries (dry run).

```bash
./lab schedule show
```

Output:
```
# lab:mealie-weekly-list
0 23 * * 5 cd /home/user/lab && ./lab run mealie-weekly-list
# lab:meal-prep-reminder
0 8 * * * cd /home/user/lab && ./lab run meal-prep-reminder
```

### `schedule install`

Install entries to user crontab.

```bash
./lab schedule install
```

- Reads `:schedule` field from each integration
- Generates crontab entries with marker comments
- Merges with existing crontab (preserves non-lab entries)

Output:
```
Installed crontab entries:
  # lab:mealie-weekly-list
  0 23 * * 5 cd /home/user/lab && ./lab run mealie-weekly-list
```

### `schedule remove`

Remove lab-managed entries from crontab.

```bash
./lab schedule remove
```

- Only removes entries with `# lab:` marker
- Preserves all other crontab entries

## Help

Show available commands:

```bash
./lab
./lab help
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Error (integration failed, command not found, etc.) |

## Environment

The script uses:
- Current working directory for relative paths
- `secrets.edn` must be in same directory as `config.edn`

## Examples

### Full Workflow

```bash
# Check configuration
./lab plan

# Start services
./lab up

# Verify running
./lab status

# Test an integration
./lab run mealie-weekly-list

# Set up automatic scheduling
./lab schedule show
./lab schedule install

# View logs
./lab logs mealie

# Stop when done
./lab down
```

### Cron Job Manual Setup

```bash
# Get the exact command that would run
./lab schedule show

# Copy to crontab manually with logging
crontab -e
# Add: 0 8 * * * cd /opt/lab && ./lab run meal-prep-reminder >> /var/log/lab.log 2>&1
```
