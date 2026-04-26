# Food Truck MCP Server

An open-source [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server that connects AI assistants (Claude, ChatGPT, and any other MCP-compatible client) to food trucks running on Square POS.

A customer scans a QR code on the truck. Their AI assistant connects to the MCP server and can answer real questions — from the truck's live catalog — without guessing:

- **"Does the pad thai have peanuts?"** → reads the actual ingredient list
- **"I'm dairy-free, what should I get?"** → filters the menu against structured dietary tags
- **"How long is the wait?"** → estimates from the truck's live active order queue

The server is multi-tenant: one process serves many trucks, each at its own URL (`/truck/{truck_id}/mcp`). The truck identity is in the URL; the Square credentials stay on the server.

> **License:** Apache 2.0 — free to use, modify, and self-host.

---

## Why this exists

Food truck window staff get asked the same questions hundreds of times a shift: *what's in this dish, is it gluten-free, how long is the wait?* They often don't know the full ingredient list off the top of their head — especially for allergens — and every Q&A interaction is time away from the kitchen.

This server lets customers self-serve those questions through an AI assistant they already have on their phone, backed by data that actually lives in the truck's POS system. Four groups benefit directly:

1. **Allergy-conscious customers** — get answers from structured ingredient data, not from memory
2. **Customers who want to order ahead** — check wait time before joining the line (order placement is phase 2)
3. **Neurodivergent customers and people who find verbal ordering hard** — use a chat interface in their own AI app instead of a loud, time-pressured window interaction
4. **Truck owners** — reduce Q&A load on window staff so they can work in the kitchen

---

## How it works

```
Customer's AI app  ←→  MCP Server  ←→  Square POS API
     (Claude)           (this repo)      (menu, orders)
```

The server exposes three MCP tools:

| Tool | What it returns |
|------|----------------|
| `get_menu` | Full catalog: names, prices, dietary tags, ingredients |
| `check_allergen` | Structured ingredient data for one item, with a guidance note to the LLM |
| `get_wait_time` | Estimated wait based on active pickup order queue |

The LLM reasons over the structured data. The server never fabricates ingredient information — if a truck hasn't entered allergen data for an item, the tool says so and directs the customer to ask staff. That honest failure mode is intentional.

---

## Prerequisites

- Java 17+
- Maven 3.8+
- A [Square developer account](https://developer.squareup.com) (free)
- Node.js 20+ (for `mcp-remote`, to connect Claude Desktop)

---

## Quick start

### 1. Clone and build

```bash
git clone https://github.com/your-username/AiOrderingMCP.git
cd AiOrderingMCP
mvn clean package
```

This produces `target/ai-ordering-mcp-0.1.0-SNAPSHOT-all.jar` — a self-contained fat jar with embedded Jetty.

### 2. Get Square sandbox credentials

1. Go to [developer.squareup.com](https://developer.squareup.com/apps) and create an app (or use an existing one)
2. Open the app → **Credentials** tab → **Sandbox** section
3. Copy the **Sandbox Access Token** and **Sandbox Location ID**

### 3. Seed some menu items

The server reads menu data from Square's catalog. Your sandbox starts empty, so add some items with ingredient data. You can use the Square sandbox dashboard, or run the included seeder:

```bash
# Edit Main.java to use your sandbox token, then run it from your IDE
# It creates three demo items with dietary preferences and ingredients
```

For allergen Q&A to work well, items need their `food_and_beverage_details` filled in (dietary preferences + ingredients). Items without this data will return an "unknown — ask staff" response, which is the correct safe behavior.

### 4. Start the server

**Single-truck mode** (simplest):

```bash
export SQUARE_ACCESS_TOKEN="EAAAl7..."
export SQUARE_LOCATION_ID="LNDA4..."
export SQUARE_ENV="sandbox"

java -jar target/ai-ordering-mcp-0.1.0-SNAPSHOT-all.jar
```

The server starts on port 8080 and registers one truck at `/truck/demo/mcp`.

**Multi-truck mode:**

```bash
cp trucks.example.json trucks.json
# Edit trucks.json with your truck IDs and credentials
export TRUCKS_CONFIG_PATH=/absolute/path/to/trucks.json
java -jar target/ai-ordering-mcp-0.1.0-SNAPSHOT-all.jar
```

See [`trucks.example.json`](trucks.example.json) for the format.

**Optional env vars:**

| Variable | Default | Description |
|----------|---------|-------------|
| `MCP_HTTP_PORT` | `8080` | Port to listen on |
| `SQUARE_ENV` | `sandbox` | `sandbox` or `production` |
| `TRUCKS_CONFIG_PATH` | — | Path to multi-truck JSON config |

### 5. Verify the server is running

```bash
curl http://localhost:8080/health
# {"status":"ok"}
```

### 6. Connect Claude Desktop

Claude Desktop connects to HTTP MCP servers via `mcp-remote`. Install it once:

```bash
npm install -g mcp-remote
```

Edit `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "demo_truck": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "http://localhost:8080/truck/demo/mcp",
        "--allow-http"
      ]
    }
  }
}
```

Fully quit Claude Desktop (⌘Q on Mac), then reopen it. The `demo_truck` connector should appear. Enable it in the conversation and try:

- *"What's on the menu?"*
- *"I have a peanut allergy — is anything safe?"*
- *"How long is the wait right now?"*
- *"I'm dairy-free, what would you recommend?"*

For multiple trucks, add an entry per truck in `mcpServers`, each pointing to a different `/truck/{truck_id}/mcp` URL.

---

## Project structure

```
src/main/java/org/personal/mcp/
├── McpServerMain.java          # Entry point: Jetty + one MCP server per truck
├── FoodTruckTools.java         # Tool implementations (get_menu, check_allergen, get_wait_time)
├── SquareFoodTruckClient.java  # Square API wrapper with 5-min menu cache
├── SquareClientFactory.java    # Lazy-initializes and caches one client per truck
├── TruckRegistry.java          # Loads truck credentials from env vars or JSON file
└── HealthServlet.java          # GET /health for liveness checks
```

**Key architectural decision:** one `McpSyncServer` instance is created per truck at startup, each mounted at its own servlet path. This means the truck identity is baked into the tool handler closures — no request-scoped context or thread-locals needed. The same `SquareClientFactory` is shared across all instances, so the per-truck Square client (and its menu cache) is initialized once and reused.

---

## Connecting to a real food truck

In production, you would:

1. **Onboard the truck owner** via Square OAuth (they click "Connect Square" on your site, authorize access, you store the token)
2. **Run this server** somewhere with HTTPS (behind nginx or a load balancer)
3. **Generate a QR code** encoding `https://your-domain.com/truck/{truck_id}/mcp`
4. **Print the QR code** — the truck tapes it up, customers scan it

The customer-side experience depends on which AI app they use and that app's support for adding MCP connectors. This is an evolving area; the QR-to-connector UX will improve as AI apps add support for it. For a merchant-facing demo today, a simple web chat UI backed by this server (and the Claude API) is the most reliable approach.

---

## What's not here yet (phase 2)

- **Order placement and payment** — needs customer identity flow and a Square-hosted payment redirect, since cards can't be entered in chat
- **Square OAuth onboarding** — truck owner self-service "Connect Square" flow with token storage
- **Encrypted token storage** — the JSON file holds plaintext tokens, fine for sandbox/dev, not for production (use a database with KMS-managed encryption at rest)
- **Web chat UI** — a merchant-branded chat UI so customers don't need a separate AI app
- **TLS on Jetty** — for production, run behind a reverse proxy (nginx, Caddy) that terminates TLS

Contributions welcome on any of these.

---

## Troubleshooting

**`ApiException: HTTP Response Not OK` from Square**
Your access token is expired or invalid. Sandbox tokens expire after 30 days. Regenerate at [developer.squareup.com](https://developer.squareup.com/apps) → your app → Credentials → Sandbox.

**Both trucks show the same menu**
Make sure your catalog items are scoped to specific locations (`present_at_all_locations: false`, `present_at_location_ids: [your_location_id]`). Items with `present_at_all_locations: true` appear for every location. The server filters by `locationId` in the Square search request.

**Claude Desktop says the config entry is not valid**
The `url` key alone is not supported in `claude_desktop_config.json`. You must use `mcp-remote` as shown in the connect instructions above.

**`ReferenceError: File is not defined` from mcp-remote**
Your Node.js version is too old. `mcp-remote` requires Node 20+. Upgrade: `nvm install 20 && nvm use 20`, then reinstall `npm install -g mcp-remote`.

**Port 8080 already in use**
Set `MCP_HTTP_PORT=8081` (or any free port) before starting the server.

**Jackson serialization error (`NoSuchFieldError on JsonFormat$Shape`)**
Jackson 2 and Jackson 3 are both on the classpath. The `pom.xml` excludes Jackson 3 from the MCP SDK's transitive dependencies. If this recurs after adding new dependencies, run `mvn dependency:tree | grep jackson` and exclude any `tools.jackson.*` artifacts from whichever dependency is pulling them in.

**Tools list shows correctly but tool calls return errors**
Check the Java server logs directly — the MCP error message is forwarded verbatim. Common causes: expired Square token (see above), or a `truck_id` in the URL that doesn't match any entry in your registry.

---

## Contributing

Pull requests welcome. If you're extending this, the most impactful areas are:

- Phase 2 order placement (the Square Orders API work is already proven in the feasibility scripts)
- A lightweight web chat UI so customers don't need a separate AI app installed
- Docker image and a one-command local setup
- Square OAuth onboarding flow

Please open an issue before starting large changes so we can align on approach.

---

## Acknowledgements

Built using:
- [Model Context Protocol Java SDK](https://github.com/modelcontextprotocol/java-sdk) — Apache 2.0
- [Square Java SDK](https://github.com/square/square-java-sdk) — Apache 2.0
- [Eclipse Jetty](https://github.com/eclipse/jetty.project) — EPL 2.0 / Apache 2.0