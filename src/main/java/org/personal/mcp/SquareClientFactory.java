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
import com.squareup.square.Environment;
import com.squareup.square.SquareClient;
import org.personal.mcp.TruckRegistry.TruckCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lazily produces and caches one {@link SquareFoodTruckClient} per truck.
 *
 * <p>Each {@code SquareFoodTruckClient} holds its own menu cache, so we want
 * exactly one instance per truck across the lifetime of the server — otherwise
 * every request would re-pull the menu from Square and we'd burn rate limit.
 *
 * <p>Before returning a cached client, checks if the token needs refresh (expiring
 * within 5 minutes) and refreshes it automatically using the refresh_token.
 *
 * <p>Thread-safety: backed by {@link ConcurrentHashMap}, and uses {@link ReentrantReadWriteLock}
 * per truck to serialize token refresh operations.
 */
public class SquareClientFactory {

    private static final Logger log = LoggerFactory.getLogger(SquareClientFactory.class);

    private final TruckRegistry registry;
    private final Map<String, SquareFoodTruckClient> clientsByTruckId = new ConcurrentHashMap<>();
    private final Map<String, ReentrantReadWriteLock> locksByTruckId = new ConcurrentHashMap<>();

    public SquareClientFactory(TruckRegistry registry) {
        this.registry = registry;
    }

    public SquareFoodTruckClient forTruck(String truckId) {
        return clientsByTruckId.computeIfAbsent(truckId, this::buildClient);
    }

    private SquareFoodTruckClient buildClient(String truckId) {
        TruckCredentials creds = registry.lookup(truckId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Unknown truck_id: '" + truckId + "'. Check the URL or registry config."
                ));

        // Check if token needs refresh before building
        if (creds.needsRefresh()) {
            creds = refreshTokenIfNeeded(truckId);
        }

        log.info("Building Square client for truck '{}' (env={})", truckId, creds.environment);
        return new SquareFoodTruckClient(
                creds.accessToken,
                creds.locationId,
                "production".equalsIgnoreCase(creds.environment) ? Environment.PRODUCTION : Environment.SANDBOX
        );
    }

    private TruckCredentials refreshTokenIfNeeded(String truckId) {
        ReentrantReadWriteLock lock = locksByTruckId.computeIfAbsent(truckId, k -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        try {
            TruckCredentials creds = registry.lookup(truckId)
                    .orElseThrow(() -> new NoSuchElementException("Unknown truck_id: '" + truckId + "'"));

            // Double-check: another thread may have already refreshed while we were waiting for the lock
            if (!creds.needsRefresh()) {
                return creds;
            }

            if (creds.refreshToken == null) {
                log.warn("Truck '{}' token expired but no refresh_token available", truckId);
                return creds;
            }

            try {
                String clientId = System.getenv("SQUARE_OAUTH_CLIENT_ID");
                String clientSecret = System.getenv("SQUARE_OAUTH_CLIENT_SECRET");
                if (clientId == null || clientSecret == null) {
                    log.warn("OAuth credentials not configured, cannot refresh token for truck '{}'", truckId);
                    return creds;
                }

                ObjectMapper json = new ObjectMapper();
                String requestBody = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                        "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) +
                        "&refresh_token=" + URLEncoder.encode(creds.refreshToken, StandardCharsets.UTF_8) +
                        "&grant_type=refresh_token";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://connect.squareup.com/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.error("Token refresh failed for truck '{}': HTTP {}", truckId, response.statusCode());
                    return creds;
                }

                JsonNode respNode = json.readTree(response.body());
                if (respNode.has("error")) {
                    log.error("Token refresh failed for truck '{}': {}", truckId, respNode.get("error").asText());
                    return creds;
                }

                String newAccessToken = respNode.get("access_token").asText();
                String newRefreshToken = respNode.has("refresh_token") && !respNode.get("refresh_token").isNull()
                        ? respNode.get("refresh_token").asText() : creds.refreshToken;
                long newExpiresAt = 0;
                if (respNode.has("expires_at") && !respNode.get("expires_at").isNull()) {
                    try {
                        newExpiresAt = java.time.Instant.parse(respNode.get("expires_at").asText()).toEpochMilli();
                    } catch (Exception e) {
                        log.warn("Could not parse token expiry for truck '{}': {}", truckId, respNode.get("expires_at"));
                    }
                }

                TruckCredentials newCreds = new TruckCredentials(newAccessToken, newRefreshToken, creds.locationId, creds.environment, newExpiresAt);

                // Update registry
                registry.register(truckId, newCreds);

                // Update trucks.json
                updateTrucksJsonToken(truckId, newAccessToken, newRefreshToken, newExpiresAt);

                // Invalidate cached client so it gets rebuilt with new token on next access
                clientsByTruckId.remove(truckId);

                log.info("Successfully refreshed token for truck '{}', expires at {}", truckId, newExpiresAt);
                return newCreds;

            } catch (Exception e) {
                log.error("Exception during token refresh for truck '{}'", truckId, e);
                return creds;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void updateTrucksJsonToken(String truckId, String accessToken, String refreshToken, long expiresAt) {
        String configPath = System.getenv("TRUCKS_CONFIG_PATH");
        if (configPath == null || configPath.isBlank()) {
            configPath = "trucks.json";
        }

        try {
            Path path = Path.of(configPath);
            if (!Files.exists(path)) {
                return;
            }

            TokenEncryption encryption = TokenEncryption.fromEnv();
            String storedAccessToken = (encryption != null) ? encryption.encrypt(accessToken) : accessToken;
            String storedRefreshToken = (encryption != null) ? encryption.encrypt(refreshToken) : refreshToken;

            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> trucks = json.readValue(Files.readAllBytes(path), Map.class);

            @SuppressWarnings("unchecked")
            Map<String, String> entry = (Map<String, String>) trucks.get(truckId);
            if (entry != null) {
                entry.put("access_token", storedAccessToken);
                entry.put("refresh_token", storedRefreshToken);
                entry.put("access_token_expires_at", String.valueOf(expiresAt));
                Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(trucks));
            }
        } catch (IOException e) {
            log.warn("Failed to update trucks.json with refreshed token for truck '{}': {}", truckId, e.getMessage());
        }
    }
}
