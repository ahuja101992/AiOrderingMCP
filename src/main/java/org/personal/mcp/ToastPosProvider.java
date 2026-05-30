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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Toast POS system provider.
 * Integrates with Toast Platform API for menu, orders, and status.
 *
 * Toast API docs: https://developer.toasttab.com/
 * Endpoints:
 *  - GET /restaurants/{restaurantId}/menu (menu items)
 *  - POST /restaurants/{restaurantId}/orders (create order)
 *  - GET /restaurants/{restaurantId}/orders/{orderId} (order status)
 */
public class ToastPosProvider implements PosProvider {

    private static final Logger log = LoggerFactory.getLogger(ToastPosProvider.class);
    private static final String TOAST_API_BASE = "https://api.toasttab.com";

    private final String accessToken;
    private final String restaurantId;
    private final String environment;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ToastPosProvider(String accessToken, String restaurantId, String environment) {
        this.accessToken = accessToken;
        this.restaurantId = restaurantId;
        this.environment = environment;
        log.info("Initialized Toast provider for restaurant {}", restaurantId);
    }

    @Override
    public List<MenuItem> getMenu(boolean refresh) throws Exception {
        String url = TOAST_API_BASE + "/restaurants/" + restaurantId + "/menu";
        JsonNode response = makeRequest("GET", url);

        List<MenuItem> items = new ArrayList<>();
        if (response.isArray()) {
            for (JsonNode item : response) {
                MenuItem menuItem = new MenuItem(
                        item.get("id").asText(),
                        item.get("name").asText(),
                        item.has("description") ? item.get("description").asText() : "",
                        item.has("price") ? item.get("price").asLong() : 0L,
                        parseIngredients(item),
                        parseDietaryPreferences(item)
                );
                items.add(menuItem);
            }
        }
        return items;
    }

    @Override
    public CreateOrderResult createOrder(String customerName, List<Map<String, Object>> items, String pickupTime) throws Exception {
        String url = TOAST_API_BASE + "/restaurants/" + restaurantId + "/orders";

        // Build Toast order request format
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("customerName", customerName);
        orderData.put("pickupTime", pickupTime);
        orderData.put("items", items);

        JsonNode response = makeRequest("POST", url, json.writeValueAsString(orderData));

        String orderId = response.get("id").asText();
        long totalMoney = response.has("total") ? response.get("total").asLong() : 0L;

        return new CreateOrderResult(orderId, totalMoney, restaurantId);
    }

    @Override
    public String getOrderStatus(String orderId) throws Exception {
        String url = TOAST_API_BASE + "/restaurants/" + restaurantId + "/orders/" + orderId;
        JsonNode response = makeRequest("GET", url);

        if (response.has("status")) {
            return response.get("status").asText();
        }
        return "unknown";
    }

    @Override
    public int getWaitTimeMinutes() throws Exception {
        // Toast doesn't provide a direct wait time estimate
        // Could calculate from active orders or use a default
        return 15; // TODO: improve with actual calculation
    }

    private JsonNode makeRequest(String method, String url) throws Exception {
        return makeRequest(method, url, null);
    }

    private JsonNode makeRequest(String method, String url, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json");

        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""));
        } else if ("GET".equals(method)) {
            builder.GET();
        }

        HttpRequest request = builder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("Toast API error: {} {}", response.statusCode(), response.body());
            throw new Exception("Toast API error: " + response.statusCode());
        }

        return json.readTree(response.body());
    }

    private List<String> parseIngredients(JsonNode item) {
        List<String> ingredients = new ArrayList<>();
        if (item.has("ingredients") && item.get("ingredients").isArray()) {
            for (JsonNode ing : item.get("ingredients")) {
                ingredients.add(ing.asText());
            }
        }
        return ingredients;
    }

    private Map<String, Boolean> parseDietaryPreferences(JsonNode item) {
        Map<String, Boolean> prefs = new HashMap<>();
        if (item.has("dietaryAttributes") && item.get("dietaryAttributes").isArray()) {
            for (JsonNode attr : item.get("dietaryAttributes")) {
                String attrName = attr.asText().toLowerCase();
                if (attrName.contains("vegan")) prefs.put("vegan", true);
                if (attrName.contains("vegetarian")) prefs.put("vegetarian", true);
                if (attrName.contains("gluten")) prefs.put("gluten_free", true);
                if (attrName.contains("dairy")) prefs.put("dairy_free", true);
            }
        }
        return prefs;
    }
}
