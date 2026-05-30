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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps a truck_id (the URL slug each food truck gets) to that truck's Square
 * credentials. In a real deployment this is backed by a database — for the MVP
 * it loads from a JSON file at startup.
 *
 * <p>Loading order:
 * <ol>
 *   <li>If {@code TRUCKS_CONFIG_PATH} env var is set, load JSON from that path.</li>
 *   <li>Else if {@code SQUARE_ACCESS_TOKEN} and {@code SQUARE_LOCATION_ID} env
 *       vars are set, register a single truck under id {@code "demo"}.
 *       This lets you run the server with the same env vars the stdio version used.</li>
 *   <li>Otherwise, fail fast with a clear error.</li>
 * </ol>
 *
 * <p>The JSON file format is a top-level object whose keys are truck ids:
 * <pre>{@code
 * {
 *   "joes_tacos": {
 *     "access_token": "EAAA...",
 *     "location_id": "L0123",
 *     "environment": "sandbox"
 *   },
 *   "burger_bus": { ... }
 * }
 * }</pre>
 */
public class TruckRegistry {

    private static final Logger log = LoggerFactory.getLogger(TruckRegistry.class);

    // ConcurrentHashMap so live registration is thread-safe
    private final Map<String, TruckCredentials> trucks;

    public TruckRegistry(Map<String, TruckCredentials> trucks) {
        this.trucks = new java.util.concurrent.ConcurrentHashMap<>(trucks);
    }

    public Optional<TruckCredentials> lookup(String truckId) {
        if (truckId == null || truckId.isBlank()) return Optional.empty();
        return Optional.ofNullable(trucks.get(truckId));
    }

    /** Register a truck at runtime without a server restart. */
    public void register(String truckId, TruckCredentials creds) {
        trucks.put(truckId, creds);
        log.info("Live-registered truck '{}'", truckId);
    }

    public int size() {
        return trucks.size();
    }

    public Iterable<String> truckIds() {
        return trucks.keySet();
    }

    /** Build from environment / config file per the loading order documented above. */
    public static TruckRegistry fromEnvironment() {
        String configPath = System.getenv("TRUCKS_CONFIG_PATH");
        if (configPath != null && !configPath.isBlank()) {
            return loadFromJsonFile(Path.of(configPath));
        }

        String token = System.getenv("SQUARE_ACCESS_TOKEN");
        String locationId = System.getenv("SQUARE_LOCATION_ID");
        if (token != null && !token.isBlank() && locationId != null && !locationId.isBlank()) {
            String envName = Optional.ofNullable(System.getenv("SQUARE_ENV")).orElse("sandbox");
            log.info("Loading single-truck demo registry from env vars (truck_id=demo)");
            return new TruckRegistry(Map.of(
                    "demo", new TruckCredentials(token, null, locationId, envName)
            ));
        }

        throw new IllegalStateException(
                "No truck configuration found. Set TRUCKS_CONFIG_PATH to a JSON file, " +
                        "or set SQUARE_ACCESS_TOKEN + SQUARE_LOCATION_ID for the single-truck demo mode."
        );
    }

    static TruckRegistry loadFromJsonFile(Path path) {
        log.info("Loading truck registry from {}", path);
        TokenEncryption encryption = TokenEncryption.fromEnv();
        ObjectMapper mapper = new ObjectMapper();
        Map<String, TruckCredentials> result = new HashMap<>();
        try (InputStream in = Files.newInputStream(path)) {
            JsonNode root = mapper.readTree(in);
            if (!root.isObject()) {
                throw new IOException("Top-level JSON must be an object: " + path);
            }
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String truckId = entry.getKey();
                JsonNode v = entry.getValue();
                String rawToken = textOrThrow(v, "access_token", truckId);
                String accessToken = (encryption != null) ? encryption.decrypt(rawToken) : rawToken;
                String rawRefresh = v.hasNonNull("refresh_token") ? v.get("refresh_token").asText() : null;
                String refreshToken = (rawRefresh != null && encryption != null)
                        ? encryption.decrypt(rawRefresh) : rawRefresh;
                String loc = textOrThrow(v, "location_id", truckId);
                String env = v.hasNonNull("environment") ? v.get("environment").asText() : "sandbox";
                result.put(truckId, new TruckCredentials(accessToken, refreshToken, loc, env));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load truck registry: " + e.getMessage(), e);
        }
        log.info("Loaded {} trucks: {}", result.size(), result.keySet());
        return new TruckRegistry(result);
    }

    private static String textOrThrow(JsonNode node, String field, String truckId) throws IOException {
        if (!node.hasNonNull(field)) {
            throw new IOException("Truck '" + truckId + "' is missing required field '" + field + "'");
        }
        return node.get(field).asText();
    }

    /** A truck's Square credentials. */
    public static final class TruckCredentials {
        public final String accessToken;
        /** Null until OAuth is implemented; reserved for token refresh. */
        public final String refreshToken;
        public final String locationId;
        public final String environment;

        public TruckCredentials(String accessToken, String refreshToken,
                                String locationId, String environment) {
            this.accessToken  = Objects.requireNonNull(accessToken, "accessToken");
            this.refreshToken = refreshToken;
            this.locationId   = Objects.requireNonNull(locationId, "locationId");
            this.environment  = environment == null ? "sandbox" : environment;
        }
    }
}
