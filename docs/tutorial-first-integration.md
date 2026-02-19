# Tutorial: Run Your First Integration

This tutorial walks you through setting up lab-orchestrator from scratch and running your first integration. By the end, you'll have created a shopping list in Mealie.

**Time:** ~15 minutes  
**Prerequisites:** Docker installed

## Step 1: Install Babashka

[Babashka](https://babashka.org/) is a fast Clojure scripting runtime. Install it:

**macOS:**
```bash
brew install borkdude/brew/babashka
```

**Linux:**
```bash
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install
sudo ./install
```

Verify it works:
```bash
bb --version
# babashka v1.3.x
```

## Step 2: Clone and Configure

Clone the repository:
```bash
git clone https://github.com/youruser/lab.git
cd lab
```

Copy the example secrets file:
```bash
cp secrets.edn.example secrets.edn
```

For this tutorial, we'll start Mealie locally, so edit `secrets.edn`:
```clojure
{:mealie-url "http://localhost:9925"
 :mealie-token "your-token-here"  ; We'll get this in Step 4
 
 :mealie {:url "http://localhost:9925"
          :token "your-token-here"}}
```

## Step 3: Start Mealie

Start the Mealie service:
```bash
./lab up
```

You should see:
```
Starting services...
[+] Running 1/1
 ✔ Container mealie  Started
```

Wait a moment for Mealie to initialize, then open http://localhost:9925 in your browser.

## Step 4: Create a User and Get API Token

1. **Create account:** Click "Create Account" and sign up
2. **Get API token:**
   - Go to Settings (gear icon) → API Tokens
   - Click "Create Token"
   - Copy the token

3. **Update secrets.edn** with your token:
```clojure
{:mealie-url "http://localhost:9925"
 :mealie-token "eyJ0eXAiOiJKV1Q..."  ; Your token here
 
 :mealie {:url "http://localhost:9925"
          :token "eyJ0eXAiOiJKV1Q..."}}
```

## Step 5: Run the Integration

Now run the weekly shopping list integration:
```bash
./lab run mealie-weekly-list
```

You should see:
```
Running integration: mealie-weekly-list
  Loading: integrations/mealie.clj
  Fetching this week's meals...
  No meals planned, creating empty list
  Created: Week of Feb 19
```

## Step 6: Verify

Go to Mealie → Shopping Lists. You should see a new list named "Week of Feb 19" (or the current date).

Congratulations! You've run your first integration.

## What Just Happened?

1. `./lab run mealie-weekly-list` looked up the integration in `config.edn`
2. Found it had a `:handler` pointing to `integrations/mealie.clj`
3. Loaded and executed that Clojure code
4. The code called Mealie's API to create a shopping list

## Next Steps

- [Run integrations on a schedule](howto-run-on-schedule.md)
- [Write your own integration](howto-write-integration.md)
- [Understand the architecture](explanation-architecture.md)
