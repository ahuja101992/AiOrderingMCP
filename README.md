# AI Ordering MCP — HTTP, multi-truck

MCP server that exposes Square-backed food trucks to LLM clients over HTTP.

## What changed from the stdio version

Previously the server ran on stdin/stdout and served exactly one truck whose
credentials came from environment variables. Now:

- **HTTP transport.** Embedded Jetty hosts the MCP servlet. The server listens
  on a port you can point Claude Desktop at (or any other MCP client that
  supports remote servers).
- **Multi-truck routing.** Each truck has its own URL: `/truck/{truck_id}/mcp`.
  A servlet filter extracts `truck_id` from the URL and the tools resolve the
  right Square credentials per request.
- **One server process for all trucks.** Same code, same tool definitions —
  the truck identity is per-request, not per-process.

## Project layout

```
src/main/java/org/personal/mcp/
├── McpServerMain.java          # entry point: Jetty + MCP wiring
├── FoodTruckTools.java         # tool implementations (truck-aware)
├── SquareFoodTruckClient.java  # one client per truck (per-instance menu cache)
├── SquareClientFactory.java    # caches one client per truck_id
├── TruckRegistry.java          # truck_id -> credentials lookup
├── RequestContext.java         # ThreadLocal carrying truck_id through the request
├── TruckIdFilter.java          # extracts truck_id from URL into RequestContext
└── HealthServlet.java          # /health for liveness checks
```

## Build

```bash
cd /Users/akshitahuja/IdeaProjects/AiOrderingMCP
mvn clean package
```

Produces `target/ai-ordering-mcp-0.1.0-SNAPSHOT-all.jar` (the fat jar).

## Run

Two modes for configuring trucks.

### Single-truck demo mode

Use the same env vars the stdio version used. The server registers the truck
under id `demo` and exposes it at `/truck/demo/mcp`.

```bash
export SQUARE_ACCESS_TOKEN="EAAA..."
export SQUARE_LOCATION_ID="L..."
export SQUARE_ENV="sandbox"            # or "production"
export MCP_HTTP_PORT=8080              # optional, default 8080

java -jar target/ai-ordering-mcp-0.1.0-SNAPSHOT-all.jar
```

### Multi-truck mode

Create a JSON file mapping truck ids to credentials. See `trucks.example.json`
in this repo for the format.

```bash
export TRUCKS_CONFIG_PATH=/path/to/trucks.json
export MCP_HTTP_PORT=8080

java -jar target/ai-ordering-mcp-0.1.0-SNAPSHOT-all.jar
```

Each key in the JSON becomes a URL path: `joes_tacos` becomes
`http://localhost:8080/truck/joes_tacos/mcp`.

## Verify it's running

```bash
curl http://localhost:8080/health
# {"status":"ok"}
```

For a more thorough check, send a real MCP initialize request:

```bash
curl -i -X POST http://localhost:8080/truck/demo/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-03-26",
      "capabilities": {},
      "clientInfo": {"name": "curl", "version": "0"}
    }
  }'
```

You should get a 200 with a JSON-RPC response listing the server's capabilities.

A request to an unknown truck returns 404:

```bash
curl -i http://localhost:8080/truck/nonexistent/mcp
# HTTP/1.1 404 Not Found
```

## Connect Claude Desktop

Edit `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "joes_tacos": {
      "url": "http://localhost:8080/truck/joes_tacos/mcp"
    }
  }
}
```

Restart Claude Desktop. The connector should appear in the panel. Try:

- "What's on the menu?"
- "I have a peanut allergy, what's safe?"
- "How long is the wait?"

If you have multiple trucks configured, you can register each one as a
separate connector in the same config. Switching between them happens just
by enabling/disabling the connector — the tools are the same, the responses
differ because the URL routes to different Square credentials.

## Testing multi-truck routing locally

The fastest way to prove the routing actually works (without setting up a
second sandbox account):

1. Create `trucks.json` with two entries pointing at the *same* sandbox
   credentials but different truck ids.
2. Start the server with `TRUCKS_CONFIG_PATH=trucks.json`.
3. In Claude Desktop config, register both URLs as separate connectors.
4. Ask each connector "what's on the menu?" — both should return identical
   menus (same Square account), but the response will include the correct
   `truck_id` field showing which connector served the request.

## What's deliberately not here yet

- **OAuth onboarding.** The truck registry is loaded from a static JSON file.
  Real onboarding (truck owner clicks "Connect Square," gets bounced through
  Square OAuth, lands a token) is the next phase.
- **Encrypted token storage.** The JSON file holds plain access tokens. Fine
  for sandbox / single-developer use, not fine for production. Real
  deployment needs a database with KMS-managed encryption.
- **Customer identity / order placement / payment.** Phase 2.
- **TLS.** Jetty is bound on plain HTTP. For real deployment put it behind
  a reverse proxy that terminates TLS, or configure Jetty with a cert.

## Troubleshooting

**Server starts but Claude Desktop can't connect.** Check the URL — Claude
expects `http://localhost:8080/truck/{truck_id}/mcp`, not just
`http://localhost:8080/`. Check Claude's MCP logs (Settings → Developer →
MCP Logs) for the exact request URL it tried.

**Tool calls return "Unknown truck_id".** The filter rejected the URL because
the truck_id isn't in the registry. Double-check spelling, and that the
config file actually loaded (look for "Loaded N truck(s)" in the logs at
startup).

**Port 8080 already in use.** Set `MCP_HTTP_PORT` to something else.

**JSON corruption / NoSuchFieldError on JsonFormat.** This is the Jackson 2
vs Jackson 3 conflict from the stdio version. The exclusions in pom.xml
should prevent it; if it comes back, run `mvn dependency:tree | grep jackson`
and look for any `tools.jackson.*` entries leaking in from a new transitive.
