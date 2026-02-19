# How to Deploy to a Server

This guide covers deploying lab-orchestrator to a remote VPS.

## Prerequisites

- A server with SSH access
- Docker installed on the server
- [Babashka](https://babashka.org/) installed on the server

## Step 1: Install Babashka on Server

SSH into your server:
```bash
ssh user@your-server.com
```

Install Babashka:
```bash
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install
sudo ./install
bb --version
```

## Step 2: Create Deployment Directory

```bash
sudo mkdir -p /opt/lab
sudo chown $USER:$USER /opt/lab
```

## Step 3: Deploy Files

From your local machine, use rsync:

```bash
rsync -avz --exclude='secrets.edn' \
  --exclude='*.log' \
  --exclude='data/' \
  --exclude='.git/' \
  ./ user@your-server.com:/opt/lab/
```

**Don't sync secrets!** Set them up separately on the server.

## Step 4: Configure Secrets on Server

SSH into server and create secrets:
```bash
ssh user@your-server.com
cd /opt/lab
cp secrets.edn.example secrets.edn
vim secrets.edn
```

Fill in production values:
```clojure
{:mealie-url "https://mealie.your-domain.com"
 :mealie-token "production-token"
 
 :mealie {:url "https://mealie.your-domain.com"
          :token "production-token"}
 
 :donetick {:url "https://donetick.your-domain.com"
            :token "production-token"}}
```

Set restrictive permissions:
```bash
chmod 600 secrets.edn
```

## Step 5: Verify Deployment

Test that everything works:
```bash
cd /opt/lab
./lab plan          # Should show compose YAML
./lab integrations  # Should list integrations
./lab run mealie-weekly-list  # Test an integration
```

## Step 6: Set Up Schedules

Install crontab entries:
```bash
./lab schedule show     # Preview
./lab schedule install  # Install
```

Verify:
```bash
crontab -l
```

## Deployment Script

Create a deploy script for repeated deployments:

`deploy.sh`:
```bash
#!/bin/bash
set -e

SERVER="user@your-server.com"
REMOTE_DIR="/opt/lab"

echo "Deploying to $SERVER..."

rsync -avz --delete \
  --exclude='secrets.edn' \
  --exclude='*.log' \
  --exclude='data/' \
  --exclude='.git/' \
  --exclude='test/' \
  ./ "$SERVER:$REMOTE_DIR/"

echo "Updating schedules..."
ssh "$SERVER" "cd $REMOTE_DIR && ./lab schedule install"

echo "Done!"
```

Run: `./deploy.sh`

## Docker Compose Services

If running services on the same server:

```bash
./lab up      # Start all services
./lab status  # Check they're running
./lab logs    # View logs
```

## Monitoring

### Check If Integrations Ran

Look at cron logs:
```bash
grep "lab run" /var/log/syslog
```

### Simple Health Check

Create a monitoring integration that pings an external service:
```clojure
:integrations
{:heartbeat
 {:type :http-call
  :schedule "*/5 * * * *"
  :action {:method :get
           :url "https://healthchecks.io/ping/your-uuid"}}}
```

### Log Output

Modify crontab to log output:
```bash
./lab schedule show
# Then manually edit to add logging:
crontab -e
```

Change:
```cron
0 8 * * * cd /opt/lab && ./lab run meal-prep-reminder
```

To:
```cron
0 8 * * * cd /opt/lab && ./lab run meal-prep-reminder >> /var/log/lab.log 2>&1
```

### Log Rotation

Create `/etc/logrotate.d/lab`:
```
/var/log/lab.log {
    weekly
    rotate 4
    compress
    missingok
    notifempty
}
```

## Security Considerations

1. **secrets.edn permissions:** `chmod 600 secrets.edn`
2. **Never commit secrets:** Ensure `.gitignore` has `secrets.edn`
3. **Use environment variables:** For CI/CD, consider `#env` tags
4. **Firewall:** Ensure only necessary ports are open

## Updating

To update the deployment:
```bash
# Local: commit changes, then:
./deploy.sh

# Or manually:
rsync -avz --exclude='secrets.edn' ./ user@server:/opt/lab/
```

## Rollback

Keep the previous version:
```bash
# Before deploying
ssh user@server "cp -r /opt/lab /opt/lab.backup"

# To rollback
ssh user@server "rm -rf /opt/lab && mv /opt/lab.backup /opt/lab"
```
