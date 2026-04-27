/*
 *
 *  * Copyright 2026 Akshit Ahuja
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.personal.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the food truck MCP server (phase 1, HTTP transport).
 *
 * <p><b>Architecture note — why one McpSyncServer per truck:</b>
 *
 * <p>The original design used a single McpSyncServer with a ThreadLocal to carry
 * the truck_id from a servlet filter into the tool callbacks. This breaks because
 * mcp-remote (and the MCP spec in general) establishes a persistent SSE session on
 * the first HTTP request and then reuses that connection for all subsequent tool
 * calls — possibly on a different thread, after the original filter has long since
 * returned and cleared the ThreadLocal.
 *
 * <p>The correct design: one {@link HttpServletStreamableServerTransportProvider}
 * and one {@link McpSyncServer} per truck, each mounted at its own servlet path
 * ({@code /truck/{truck_id}/mcp}). The truck_id is captured in a closure at startup
 * and baked into the tool handlers for that server instance. No ThreadLocal, no
 * filter, no session lifecycle surprises.
 *
 * <p>Memory: each server instance is tiny — a transport, a server object, and a
 * reference to the shared {@link SquareClientFactory}. Adding 100 trucks adds 100
 * lightweight objects, not 100 thread pools.
 */
public class McpServerMain {

    private static final Logger log = LoggerFactory.getLogger(McpServerMain.class);
    private static final ObjectMapper json = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        TruckRegistry registry = TruckRegistry.fromEnvironment();
        log.info("Loaded {} truck(s): {}", registry.size(), registry.truckIds());

        SquareClientFactory clientFactory = new SquareClientFactory(registry);

        int port = parsePort();
        Server jetty = new Server(port);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        // One McpSyncServer per truck. The truck_id is captured in the closure
        // passed to each tool handler, so it's always correct regardless of which
        // thread or connection the tool call arrives on.
        List<McpSyncServer> mcpServers = new ArrayList<>();
        for (String truckId : registry.truckIds()) {
            McpSyncServer mcpServer = buildTruckServer(truckId, clientFactory, context);
            mcpServers.add(mcpServer);
            log.info("Registered truck '{}' at /truck/{}/mcp", truckId, truckId);
        }

        // Live registration callback
        RegisterServlet.NewTruckCallback onNewTruck = (truckId, creds) -> {
            try {
                McpSyncServer newServer = buildTruckServer(truckId, clientFactory, context);
                mcpServers.add(newServer);
                log.info("Live-added MCP server for new truck '{}'", truckId);
            } catch (Exception e) {
                log.error("Failed to start MCP server for truck '{}'", truckId, e);
            }
        };

        // UI + API servlets (order matters: specific paths before wildcard)
        context.addServlet(new ServletHolder(new RegisterServlet(registry, onNewTruck)), "/api/register");
        context.addServlet(new ServletHolder(new ChatServlet(registry)),                 "/api/chat");
        context.addServlet(new ServletHolder(new TruckInfoServlet(registry)),            "/api/trucks/*");
        context.addServlet(new ServletHolder(new HealthServlet()),                       "/health");
        context.addServlet(new ServletHolder(new StaticFileServlet()),                   "/*");

        jetty.setHandler(context);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            for (McpSyncServer s : mcpServers) {
                try { s.close(); } catch (Exception e) { log.warn("error closing server", e); }
            }
            try { jetty.stop(); } catch (Exception e) { log.warn("error stopping jetty", e); }
        }));

        jetty.start();
        log.info("Foodtruck MCP server listening on http://localhost:{}", port);
        for (String truckId : registry.truckIds()) {
            log.info("  http://localhost:{}/truck/{}/mcp", port, truckId);
        }
        jetty.join();
    }

    /**
     * Build one complete MCP server for one truck and mount it on the Jetty context.
     * The truckId is captured in every tool handler closure — that's the key insight
     * that makes this architecture work without a ThreadLocal.
     */
    private static McpSyncServer buildTruckServer(
            String truckId,
            SquareClientFactory clientFactory,
            ServletContextHandler context) {

        // Tools for this specific truck. The truckId is fixed for this instance.
        FoodTruckTools tools = new FoodTruckTools(clientFactory, truckId);

        var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                .mcpEndpoint("/mcp")
                .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("foodtruck-" + truckId, "0.1.0")
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .logging()
                        .build())
                .tools(
                        getMenuToolSpec(tools),
                        checkAllergenToolSpec(tools),
                        getWaitTimeToolSpec(tools)
                )
                .build();

        // Mount this truck's servlet at /truck/{truckId}/*
        // The transport's mcpEndpoint "/mcp" means it responds to requests
        // whose path ends in /mcp, so the full URL is /truck/{truckId}/mcp.
        context.addServlet(new ServletHolder(transport), "/truck/" + truckId + "/*");

        return server;
    }

    // ---------- tool specifications ----------

    private static McpServerFeatures.SyncToolSpecification getMenuToolSpec(FoodTruckTools tools) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of("refresh", Map.of(
                        "type", "boolean",
                        "description", "If true, bypass the menu cache and fetch fresh from the POS.",
                        "default", false
                )),
                List.of(), false, null, null
        );
        Tool tool = Tool.builder()
                .name("get_menu")
                .description(
                        "Returns the food truck's full menu. Each item includes name, " +
                        "description, price, dietary preferences (e.g. vegan, gluten_free, " +
                        "dairy_free), and ingredients. Use this when the customer asks " +
                        "what's available, what's popular, or wants to browse options. " +
                        "When answering allergen questions, prefer check_allergen which " +
                        "returns the same data with a usage hint."
                )
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Object refreshArg = request.arguments() != null ? request.arguments().get("refresh") : null;
                    boolean refresh = refreshArg instanceof Boolean b ? b : false;
                    return wrapJson(tools.getMenu(refresh));
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification checkAllergenToolSpec(FoodTruckTools tools) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "item_id",   Map.of("type", "string", "description", "The Square catalog item ID, from get_menu."),
                        "item_name", Map.of("type", "string", "description", "Alternative to item_id — the item's name (case-insensitive).")
                ),
                List.of(), false, null, null
        );
        Tool tool = Tool.builder()
                .name("check_allergen")
                .description(
                        "Look up a specific menu item's ingredients and dietary tags so " +
                        "you can determine whether it's safe for someone with a given " +
                        "allergen or dietary restriction. Returns structured data — you " +
                        "should reason over it and explain to the customer in plain " +
                        "language. IMPORTANT: if ingredients and dietary_preferences are " +
                        "both empty, tell the customer the truck hasn't entered allergen " +
                        "data for this item and they should ask staff directly. Never " +
                        "fabricate ingredient information. For severe allergies, always " +
                        "recommend the customer confirm with staff regardless."
                )
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> a = request.arguments() != null ? request.arguments() : Map.of();
                    return wrapJson(tools.checkAllergen(stringOrNull(a.get("item_id")), stringOrNull(a.get("item_name"))));
                })
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification getWaitTimeToolSpec(FoodTruckTools tools) {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
                "object", Map.of(), List.of(), false, null, null
        );
        Tool tool = Tool.builder()
                .name("get_wait_time")
                .description(
                        "Returns the current estimated wait time for a pickup order. " +
                        "Approximate — based on the number of active orders and their prep times. " +
                        "Use when the customer asks how long they'll wait or whether to order ahead."
                )
                .inputSchema(schema)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> wrapJson(tools.getWaitTime()))
                .build();
    }

    // ---------- helpers ----------

    private static CallToolResult wrapJson(Object value) {
        try {
            String text = json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            return CallToolResult.builder().addContent(new TextContent(text)).build();
        } catch (Exception e) {
            log.error("failed to serialize tool result", e);
            ObjectNode err = json.createObjectNode();
            err.put("error", "serialization failure: " + e.getMessage());
            return CallToolResult.builder()
                    .addContent(new TextContent(err.toString()))
                    .isError(true)
                    .build();
        }
    }

    private static String stringOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    private static int parsePort() {
        String raw = System.getenv("MCP_HTTP_PORT");
        if (raw == null || raw.isBlank()) return 8080;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { log.warn("Bad MCP_HTTP_PORT '{}', using 8080", raw); return 8080; }
    }
}
