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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Collectors;

/**
 * POST /api/chat
 *
 * Receives:
 *   { "truck_id": "demo",
 *     "messages": [{"role": "user", "content": "What's on the menu?"}],
 *     "public_base_url": "https://abc.ngrok-free.app" }
 *
 * Calls Claude's Messages API with the truck's MCP server registered as a
 * tool source. The MCP connector beta (mcp-client-2025-11-20) lets Anthropic's
 * infrastructure make server-side tool calls to our Java server — which is why
 * the server must be at a public HTTPS URL (ngrok for local dev).
 *
 * Returns a streaming text/event-stream response so the chat UI can show
 * words appearing as Claude generates them.
 *
 * The Anthropic API key lives in the ANTHROPIC_API_KEY env var on the server —
 * it never travels to the browser.
 */
public class ChatServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ChatServlet.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final HttpClient http = HttpClient.newHttpClient();

    private static final String CLAUDE_MODEL = "claude-sonnet-4-6";

    private static final String MCP_BETA     = "mcp-client-2025-11-20";
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";

    private final TruckRegistry registry;

    public ChatServlet(TruckRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            error(resp, 500, "ANTHROPIC_API_KEY not configured on server.");
            return;
        }

        String body = req.getReader().lines().collect(Collectors.joining());
        JsonNode payload = json.readTree(body);

        String truckId       = textOrNull(payload, "truck_id");
        String publicBaseUrl = textOrNull(payload, "public_base_url");
        JsonNode messages    = payload.get("messages");

        if (truckId == null || messages == null) {
            error(resp, 400, "truck_id and messages are required.");
            return;
        }
        if (registry.lookup(truckId).isEmpty()) {
            error(resp, 404, "Unknown truck: " + truckId);
            return;
        }

        // If the client doesn't supply a public base URL, fall back to the
        // request's own host. This works when the server is already public
        // (deployed), but not for localhost — ngrok is needed in that case.
        String base = (publicBaseUrl != null && !publicBaseUrl.isBlank())
                ? publicBaseUrl.replaceAll("/+$", "")
                : req.getScheme() + "://" + req.getHeader("host");

        String mcpUrl = base + "/truck/" + truckId + "/mcp";
        log.info("Chat for truck='{}' mcp_url='{}'", truckId, mcpUrl);

        // Build the Claude API request body
        ObjectNode claudeBody = json.createObjectNode();
        claudeBody.put("model", CLAUDE_MODEL);
        claudeBody.put("max_tokens", 1024);
        claudeBody.put("stream", true);

        // System prompt gives Claude its role and safety guardrails
        claudeBody.put("system",
                "You are a helpful assistant for a food truck. " +
                "Use the available MCP tools to answer customer questions about the menu, " +
                "ingredients, allergens, and wait times. " +
                "Always use the tools to get real data — never guess about ingredients or allergens. " +
                "If allergen data is missing for an item, say so clearly and recommend the customer " +
                "confirm with staff. Keep responses concise and friendly.");

        claudeBody.set("messages", messages);

        // MCP server registration — Anthropic's infrastructure calls our Java server
        ArrayNode mcpServers = claudeBody.putArray("mcp_servers");
        ObjectNode mcpServer = mcpServers.addObject();
        mcpServer.put("type", "url");
        mcpServer.put("url",  mcpUrl);
        mcpServer.put("name", "foodtruck");

        // MCPToolset tells Claude to use all tools from the server
        ArrayNode tools = claudeBody.putArray("tools");
        ObjectNode toolset = tools.addObject();
        toolset.put("type", "mcp_toolset");
        toolset.put("mcp_server_name", "foodtruck");

        // Beta header required for MCP connector
        HttpRequest claudeReq = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API))
                .header("Content-Type",     "application/json")
                .header("x-api-key",        apiKey)
                .header("anthropic-version","2023-06-01")
                .header("anthropic-beta",   MCP_BETA)
                .POST(HttpRequest.BodyPublishers.ofString(claudeBody.toString()))
                .build();

        // Stream Claude's SSE response back to the browser
        resp.setContentType("text/event-stream");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("X-Accel-Buffering", "no");
        PrintWriter writer = resp.getWriter();

        try {
            HttpResponse<InputStream> claudeResp = http.send(
                    claudeReq, HttpResponse.BodyHandlers.ofInputStream());

            if (claudeResp.statusCode() != 200) {
                String errBody = new String(claudeResp.body().readAllBytes());
                log.error("Claude API error {}: {}", claudeResp.statusCode(), errBody);
                writer.write("data: " + json.writeValueAsString(
                        json.createObjectNode().put("error", "Claude API error: " + claudeResp.statusCode())
                ) + "\n\n");
                writer.flush();
                return;
            }

            // Forward the SSE stream from Claude to the browser.
            // We parse each event to extract text deltas and filter out
            // MCP tool call internals the customer doesn't need to see.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(claudeResp.body()))) {

                String line;
                StringBuilder eventData = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        eventData.append(line.substring(6));
                    } else if (line.isEmpty() && eventData.length() > 0) {
                        String data = eventData.toString();
                        eventData.setLength(0);

                        if (data.equals("[DONE]")) {
                            writer.write("data: [DONE]\n\n");
                            writer.flush();
                            break;
                        }

                        try {
                            JsonNode event = json.readTree(data);
                            String type = event.has("type") ? event.get("type").asText() : "";

                            // Forward text deltas — skip tool use internals
                            if ("content_block_delta".equals(type)) {
                                JsonNode delta = event.get("delta");
                                if (delta != null && "text_delta".equals(
                                        delta.has("type") ? delta.get("type").asText() : "")) {
                                    ObjectNode out = json.createObjectNode();
                                    out.put("type", "text");
                                    out.put("text", delta.get("text").asText());
                                    writer.write("data: " + json.writeValueAsString(out) + "\n\n");
                                    writer.flush();
                                }
                            } else if ("message_stop".equals(type)) {
                                writer.write("data: [DONE]\n\n");
                                writer.flush();
                            }
                        } catch (Exception e) {
                            // Malformed event — skip it
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Chat request interrupted");
        } catch (Exception e) {
            log.error("Chat error", e);
            writer.write("data: " + json.writeValueAsString(
                    json.createObjectNode().put("error", e.getMessage())
            ) + "\n\n");
            writer.flush();
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        // Allow CORS for local dev (ngrok URL differs from page origin)
        resp.setHeader("Access-Control-Allow-Origin",  "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(204);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        String v = node.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static void error(HttpServletResponse resp, int status, String msg)
            throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }
}
