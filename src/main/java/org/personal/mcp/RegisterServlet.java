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
import com.squareup.square.Environment;
import com.squareup.square.SquareClient;
import com.squareup.square.models.SearchCatalogItemsRequest;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * POST /api/register
 *
 * Body (JSON):
 *   { "name": "Joe's Tacos", "truck_id": "joes_tacos",
 *     "access_token": "EAAA...", "location_id": "L...", "environment": "sandbox" }
 *
 * Response (JSON):
 *   { "truck_id": "joes_tacos", "mcp_url": "http://host/truck/joes_tacos/mcp",
 *     "chat_url": "http://host/chat/joes_tacos" }
 *
 * The handler:
 *   1. Validates the Square token by making a lightweight catalog call.
 *   2. Writes the entry to trucks.json (creating the file if needed).
 *   3. Registers the truck live in the running TruckRegistry so no restart is needed.
 *   4. Asks McpServerMain to spin up a new McpSyncServer for this truck.
 */
public class RegisterServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(RegisterServlet.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final TruckRegistry registry;
    private final NewTruckCallback newTruckCallback;
    private final ConcurrentHashMap<String, OAuthState> oauthStates;

    /** Called when a new truck is successfully registered. */
    public interface NewTruckCallback {
        void onNewTruck(String truckId, TruckRegistry.TruckCredentials creds);
    }

    public RegisterServlet(TruckRegistry registry, NewTruckCallback newTruckCallback,
                           ConcurrentHashMap<String, OAuthState> oauthStates) {
        this.registry = registry;
        this.newTruckCallback = newTruckCallback;
        this.oauthStates = oauthStates;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json; charset=utf-8");

        // Parse body
        String body = req.getReader().lines().collect(Collectors.joining());
        var node = json.readTree(body);

        String name        = text(node, "name");
        String truckId     = text(node, "truck_id");
        String accessToken = text(node, "access_token");
        String locationId  = text(node, "location_id");
        String environment = node.hasNonNull("environment")
                ? node.get("environment").asText() : "sandbox";

        // Basic validation
        if (name == null || truckId == null) {
            error(resp, 400, "name and truck_id are required.");
            return;
        }

        // Sanitise truck_id: lowercase alphanumeric + underscores only
        String sanitised = truckId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (!sanitised.equals(truckId)) {
            error(resp, 400, "truck_id may only contain lowercase letters, digits, and underscores. Suggested: " + sanitised);
            return;
        }

        // Check if this is legacy token entry or OAuth flow request
        if (accessToken != null && locationId != null) {
            // Legacy path: direct token entry
            handleDirectTokenEntry(resp, truckId, name, accessToken, locationId, environment);
        } else {
            // OAuth path: initiate Square OAuth flow
            handleOAuthInitiation(resp, req, truckId, name, environment);
        }
    }

    private void handleDirectTokenEntry(HttpServletResponse resp, String truckId, String name,
                                        String accessToken, String locationId, String environment)
            throws IOException {
        // Validate the Square token by making a real (but cheap) API call
        if (!validateSquareToken(accessToken, locationId, environment)) {
            error(resp, 422, "Could not verify Square credentials. Check your access token and location ID.");
            return;
        }

        // Write to trucks.json (encrypt token if key is available)
        String configPath = System.getenv("TRUCKS_CONFIG_PATH");
        if (configPath == null || configPath.isBlank()) {
            configPath = "trucks.json";
        }
        TokenEncryption encryption = TokenEncryption.fromEnv();
        String storedToken = (encryption != null) ? encryption.encrypt(accessToken) : accessToken;
        writeTrucksJson(Path.of(configPath), truckId, name, storedToken, locationId, environment);

        // Register live with the decrypted token so the Square client can use it
        TruckRegistry.TruckCredentials creds =
                new TruckRegistry.TruckCredentials(accessToken, null, locationId, environment);
        registry.register(truckId, creds);
        newTruckCallback.onNewTruck(truckId, creds);

        log.info("Registered new truck '{}' (direct token) ({})", truckId, name);

        // Build response
        String host = "http://localhost:8080"; // Placeholder
        ObjectNode result = json.createObjectNode();
        result.put("truck_id",  truckId);
        result.put("name",      name);
        result.put("mcp_url",   host + "/truck/" + truckId + "/mcp");
        result.put("chat_url",  host + "/chat/" + truckId);

        resp.setStatus(200);
        resp.getWriter().write(json.writeValueAsString(result));
    }

    private void handleOAuthInitiation(HttpServletResponse resp, HttpServletRequest req,
                                       String truckId, String name, String environment)
            throws IOException {
        String clientId = System.getenv("SQUARE_OAUTH_CLIENT_ID");
        if (clientId == null || clientId.isBlank()) {
            error(resp, 500, "OAuth is not configured on this server (SQUARE_OAUTH_CLIENT_ID missing).");
            return;
        }

        // Generate state token for CSRF protection
        String state = generateState();
        oauthStates.put(state, new OAuthState(state, name, truckId, environment));

        // Build Square OAuth authorize URL
        String redirectUri = req.getScheme() + "://" + req.getHeader("host") + "/oauth-callback";
        String scope = "MERCHANT_PROFILE_READ,ORDERS_READ,ORDERS_WRITE";
        String authorizeUrl = "https://squareup.com/oauth2/authorize" +
                "?client_id=" + clientId +
                "&scope=" + java.net.URLEncoder.encode(scope, "UTF-8") +
                "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, "UTF-8") +
                "&state=" + state;

        log.info("Initiating OAuth for truck '{}', redirecting to Square", truckId);

        ObjectNode result = json.createObjectNode();
        result.put("authorize_url", authorizeUrl);
        result.put("state", state);

        resp.setStatus(200);
        resp.getWriter().write(json.writeValueAsString(result));
    }

    private static String generateState() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return bytesToHex(bytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean validateSquareToken(String token, String locationId, String env) {
        try {
            SquareClient client = new SquareClient.Builder()
                    .accessToken(token)
                    .environment("production".equalsIgnoreCase(env)
                            ? Environment.PRODUCTION : Environment.SANDBOX)
                    .build();
            // Cheapest call that requires auth: list at most 1 item
            client.getCatalogApi().searchCatalogItems(
                    new SearchCatalogItemsRequest.Builder()
                            .productTypes(List.of("FOOD_AND_BEV"))
                            .limit(1)
                            .build());
            return true;
        } catch (Exception e) {
            log.warn("Square token validation failed: {}", e.getMessage());
            return false;
        }
    }

    private void writeTrucksJson(Path path, String truckId, String name,
                                  String token, String locationId, String env)
            throws IOException {
        // Read existing file or start fresh
        Map<String, Object> trucks = new LinkedHashMap<>();
        if (Files.exists(path)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing =
                        json.readValue(Files.readAllBytes(path), Map.class);
                trucks.putAll(existing);
            } catch (Exception e) {
                log.warn("Could not parse existing trucks.json, overwriting: {}", e.getMessage());
            }
        }

        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("name",         name);
        entry.put("access_token", token);
        entry.put("location_id",  locationId);
        entry.put("environment",  env);
        trucks.put(truckId, entry);

        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(trucks));
        log.info("Updated trucks.json at {}", path.toAbsolutePath());
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        String v = node.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static void error(HttpServletResponse resp, int status, String msg)
            throws IOException {
        resp.setStatus(status);
        resp.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }
}
