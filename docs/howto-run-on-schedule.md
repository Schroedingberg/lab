# How to Run Integrations on a Schedule

This guide shows how to run integrations automatically using cron or systemd timers.

## Option 1: Built-in Schedule Command (Recommended)

Lab can manage crontab entries for you based on the `:schedule` field in config.edn.

### View Scheduled Integrations

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

### Install to Crontab

```bash
./lab schedule install
```

This adds entries to your user crontab with marker comments (`# lab:name`) so they can be managed.

### Remove Lab Entries

```bash
./lab schedule remove
```

Removes only lab-managed entries, leaving your other crontab entries intact.

## Option 2: Manual Crontab

Edit your crontab directly:
```bash
crontab -e
```

Add entries with **full paths**:
```cron
# Meal prep reminder - 8am daily
0 8 * * * cd /opt/lab && ./lab run meal-prep-reminder >> /var/log/lab-meal-prep.log 2>&1

# Weekly shopping list - Friday 11pm
0 23 * * 5 cd /opt/lab && ./lab run mealie-weekly-list >> /var/log/lab-weekly-list.log 2>&1
```

### Cron Syntax

```
┌───────────── minute (0-59)
│ ┌───────────── hour (0-23)
│ │ ┌───────────── day of month (1-31)
│ │ │ ┌───────────── month (1-12)
│ │ │ │ ┌───────────── day of week (0-6, Sunday=0)
│ │ │ │ │
* * * * * command
```

Common examples:
- `0 8 * * *` - Daily at 8am
- `0 23 * * 5` - Fridays at 11pm
- `*/15 * * * *` - Every 15 minutes
- `0 9 * * 1-5` - Weekdays at 9am

## Option 3: Systemd Timer

For more control and better logging, use systemd.

### Create the Service Unit

`/etc/systemd/user/lab-meal-prep.service`:
```ini
[Unit]
Description=Lab meal prep reminder

[Service]
Type=oneshot
WorkingDirectory=/opt/lab
ExecStart=/opt/lab/lab run meal-prep-reminder
StandardOutput=journal
StandardError=journal
```

### Create the Timer Unit

`/etc/systemd/user/lab-meal-prep.timer`:
```ini
[Unit]
Description=Run meal prep reminder daily at 8am

[Timer]
OnCalendar=*-*-* 08:00:00
Persistent=true

[Install]
WantedBy=timers.target
```

### Enable and Start

```bash
systemctl --user daemon-reload
systemctl --user enable lab-meal-prep.timer
systemctl --user start lab-meal-prep.timer
```

### Check Status

```bash
# List active timers
systemctl --user list-timers

# View logs
journalctl --user -u lab-meal-prep.service
```

## Troubleshooting

### PATH Issues

Cron runs with a minimal PATH. If commands aren't found:
```cron
PATH=/usr/local/bin:/usr/bin:/bin
0 8 * * * cd /opt/lab && ./lab run meal-prep-reminder
```

Or use absolute paths:
```cron
0 8 * * * cd /opt/lab && /usr/local/bin/bb lab run meal-prep-reminder
```

### Secrets Not Found

Cron doesn't inherit your shell environment. Ensure secrets.edn uses absolute paths or is in the same directory as the lab script.

### Debugging

1. **Check if cron ran:**
   ```bash
   grep CRON /var/log/syslog
   ```

2. **Test manually first:**
   ```bash
   cd /opt/lab && ./lab run meal-prep-reminder
   ```

3. **Capture output:**
   ```cron
   0 8 * * * cd /opt/lab && ./lab run meal-prep-reminder >> /tmp/lab.log 2>&1
   ```

4. **Check mail:**
   By default cron mails output to the user:
   ```bash
   mail
   ```

### Permissions

Ensure the script is executable:
```bash
chmod +x /opt/lab/lab
```
