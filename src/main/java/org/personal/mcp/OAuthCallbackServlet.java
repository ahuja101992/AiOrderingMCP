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
import com.squareup.square.SquareClient;
import com.squareup.square.api.OAuthApi;
import com.squareup.square.models.ObtainTokenRequest;
import com.squareup.square.models.ObtainTokenResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GET /oauth-callback
 *
 * Square redirects here after user authorizes OAuth.
 * Query params: code, state
 *
 * Exchanges code for access_token + refresh_token, stores in trucks.json,
 * and redirects user to success page with QR code.
 */
public class OAuthCallbackServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackServlet.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final ConcurrentHashMap<String, OAuthState> oauthStates;
    private final TruckRegistry registry;
    private final RegisterServlet.NewTruckCallback onNewTruck;

    public OAuthCallbackServlet(ConcurrentHashMap<String, OAuthState> oauthStates,
                                TruckRegistry registry,
                                RegisterServlet.NewTruckCallback onNewTruck) {
        this.oauthStates  = oauthStates;
        this.registry     = registry;
        this.onNewTruck   = onNewTruck;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html; charset=utf-8");

        String code  = req.getParameter("code");
        String state = req.getParameter("state");
        String error = req.getParameter("error");

        // User denied authorization
        if (error != null) {
            error(resp, "Authorization denied: " + error);
            return;
        }

        if (code == null || state == null) {
            error(resp, "Missing code or state parameter");
            return;
        }

        OAuthState oauthState = oauthStates.get(state);
        if (oauthState == null) {
            error(resp, "Invalid or expired state — please start over");
            return;
        }

        if (oauthState.isExpired()) {
            oauthStates.remove(state);
            error(resp, "Authorization request expired — please start over");
            return;
        }

        // Exchange code for tokens
        String clientId     = System.getenv("SQUARE_OAUTH_CLIENT_ID");
        String clientSecret = System.getenv("SQUARE_OAUTH_CLIENT_SECRET");

        if (clientId == null || clientSecret == null) {
            log.error("SQUARE_OAUTH_CLIENT_ID or SQUARE_OAUTH_CLIENT_SECRET not configured");
            error(resp, "Server misconfigured — OAuth credentials missing");
            return;
        }

        try {
            SquareClient squareClient = new SquareClient.Builder().build();
            String redirectUrl = req.getScheme() + "://" + req.getHeader("host") + "/oauth-callback";

            ObtainTokenRequest tokenReq = new ObtainTokenRequest.Builder(clientId, clientSecret)
                    .code(code)
                    .redirectUri(redirectUrl)
                    .build();

            ObtainTokenResponse tokenResp = squareClient.getOAuthApi().obtainToken(tokenReq);

            if (tokenResp.getErrors() != null && !tokenResp.getErrors().isEmpty()) {
                log.error("OAuth token exchange failed: {}", tokenResp.getErrors());
                error(resp, "Failed to exchange code for token");
                return;
            }

            String accessToken       = tokenResp.getAccessToken();
            String refreshToken      = tokenResp.getRefreshToken();
            String expiresAtStr      = tokenResp.getExpiresAt();
            long accessTokenExpiresAt = 0;
            if (expiresAtStr != null && !expiresAtStr.isBlank()) {
                try {
                    accessTokenExpiresAt = java.time.Instant.parse(expiresAtStr).toEpochMilli();
                } catch (Exception e) {
                    log.warn("Could not parse token expiry: {}", expiresAtStr);
                }
            }

            // Get location from merchant's first enabled location
            String locationId = null;
            try {
                var locResp = squareClient.getLocationsApi().listLocations();
                if (locResp.getLocations() != null && !locResp.getLocations().isEmpty()) {
                    locationId = locResp.getLocations().get(0).getId();
                }
            } catch (Exception e) {
                log.warn("Could not fetch locations; using placeholder", e);
                locationId = "LOCATION_ID_PLACEHOLDER";
            }

            // Encrypt and store
            TokenEncryption encryption = TokenEncryption.fromEnv();
            String storedAccessToken   = (encryption != null) ? encryption.encrypt(accessToken) : accessToken;
            String storedRefreshToken  = (encryption != null) ? encryption.encrypt(refreshToken) : refreshToken;

            // Write to trucks.json
            String configPath = System.getenv("TRUCKS_CONFIG_PATH");
            if (configPath == null || configPath.isBlank()) {
                configPath = "trucks.json";
            }

            Map<String, Object> trucks = new LinkedHashMap<>();
            Path configFile = Path.of(configPath);
            if (Files.exists(configFile)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existing = json.readValue(Files.readAllBytes(configFile), Map.class);
                    trucks.putAll(existing);
                } catch (Exception e) {
                    log.warn("Could not parse existing trucks.json, overwriting: {}", e.getMessage());
                }
            }

            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name",                  oauthState.truckName);
            entry.put("access_token",          storedAccessToken);
            entry.put("refresh_token",         storedRefreshToken);
            entry.put("access_token_expires_at", String.valueOf(accessTokenExpiresAt));
            entry.put("location_id",           locationId);
            entry.put("environment",           oauthState.environment);
            trucks.put(oauthState.truckId, entry);

            Files.writeString(configFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(trucks));
            log.info("OAuth registered truck '{}' with location '{}'", oauthState.truckId, locationId);

            // Register live
            TruckRegistry.TruckCredentials creds = new TruckRegistry.TruckCredentials(
                    accessToken, refreshToken, locationId, oauthState.environment, accessTokenExpiresAt);
            registry.register(oauthState.truckId, creds);
            onNewTruck.onNewTruck(oauthState.truckId, creds);

            // Clean up state
            oauthStates.remove(state);

            // Redirect to success page with QR code
            String chatUrl = req.getScheme() + "://" + req.getHeader("host") + "/chat/" + oauthState.truckId;
            resp.sendRedirect("/onboard?success=true&truck_id=" + oauthState.truckId + "&chat_url=" +
                    java.net.URLEncoder.encode(chatUrl, "UTF-8"));

        } catch (Exception e) {
            log.error("OAuth callback failed", e);
            error(resp, "Unexpected error: " + e.getMessage());
        }
    }

    private void error(HttpServletResponse resp, String msg) throws IOException {
        resp.setStatus(400);
        resp.getWriter().write("<html><body style='font-family:sans-serif;padding:2rem'>" +
                "<h2>❌ Authorization failed</h2><p>" + msg + "</p>" +
                "<a href='/onboard'>← Back to onboarding</a></body></html>");
    }
}
