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

import org.personal.mcp.SquareFoodTruckClient.LineItemRequest;
import org.personal.mcp.SquareFoodTruckClient.MenuItem;
import org.personal.mcp.SquareFoodTruckClient.OrderResult;
import org.personal.mcp.SquareFoodTruckClient.WaitEstimate;

import java.time.Instant;
import java.util.*;

/**
 * Business logic for each MCP tool, decoupled from the MCP transport layer.
 *
 * <p>Each instance is bound to one truck at construction time. The truckId is
 * passed in rather than read from a ThreadLocal, which is the fix for the
 * session-reuse bug: mcp-remote holds a persistent SSE connection and calls
 * tools on threads that have no filter context. Capturing truckId in the
 * constructor means it's always correct regardless of which thread the call
 * arrives on.
 */
public class FoodTruckTools {

    private final SquareClientFactory clientFactory;
    private final String truckId;

    public FoodTruckTools(SquareClientFactory clientFactory, String truckId) {
        this.clientFactory = clientFactory;
        this.truckId = truckId;
    }

    private SquareFoodTruckClient currentClient() {
        return clientFactory.forTruck(truckId);
    }

    public Map<String, Object> getMenu(boolean refresh) {
        SquareFoodTruckClient square = currentClient();
        List<MenuItem> items = square.getMenu(refresh);
        List<Map<String, Object>> serialized = new ArrayList<>(items.size());
        for (MenuItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.id);
            m.put("variation_id", item.variationId);
            m.put("name", item.name);
            m.put("description", item.description);
            m.put("price", item.formattedPrice());
            m.put("dietary_preferences", item.dietaryPreferences);
            m.put("ingredients", item.ingredients);
            m.put("calorie_count", item.calorieCount);
            serialized.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("truck_id", truckId);
        result.put("item_count", serialized.size());
        result.put("items", serialized);
        return result;
    }

    public Map<String, Object> checkAllergen(String itemId, String itemName) {
        if ((itemId == null || itemId.isBlank()) && (itemName == null || itemName.isBlank())) {
            return Map.of("error", "Provide either item_id or item_name.");
        }

        SquareFoodTruckClient square = currentClient();
        Optional<MenuItem> match = Optional.empty();
        if (itemId != null && !itemId.isBlank()) {
            match = square.getItemById(itemId);
        }
        if (match.isEmpty() && itemName != null && !itemName.isBlank()) {
            match = square.getItemByName(itemName);
        }

        if (match.isEmpty()) {
            String key = (itemId != null && !itemId.isBlank()) ? itemId : itemName;
            return Map.of("error", "No item found for '" + key + "'.");
        }

        MenuItem item = match.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("item_id", item.id);
        result.put("item_name", item.name);
        result.put("ingredients", item.ingredients);
        result.put("dietary_preferences", item.dietaryPreferences);
        result.put("has_allergen_data", item.hasAllergenData());
        result.put("guidance",
                "Reason over ingredients and dietary_preferences to answer the " +
                        "customer's allergen question. If has_allergen_data is false, " +
                        "tell the customer the truck hasn't recorded ingredient details " +
                        "for this item and they should ask staff. For any severe allergy, " +
                        "remind the customer to confirm with staff directly even when " +
                        "data is present, since cross-contamination isn't reflected here."
        );
        return result;
    }

    /**
     * Creates a pickup order on Square for kitchen display.
     * Used by the create_order MCP tool (AI-driven ordering flow).
     *
     * @param customerName display name on receipt
     * @param items        list of maps with keys "variation_id" and "quantity"
     * @param pickupTime   ISO 8601 timestamp, must be ≥ 15 minutes from now
     */
    public Map<String, Object> createOrder(String customerName,
                                            List<Map<String, Object>> items,
                                            String pickupTime) {
        if (customerName == null || customerName.isBlank()) {
            return Map.of("error", "customer_name is required.");
        }
        if (items == null || items.isEmpty()) {
            return Map.of("error", "items must be a non-empty list.");
        }
        if (pickupTime == null || pickupTime.isBlank()) {
            return Map.of("error", "pickup_time is required (ISO 8601).");
        }

        Instant pickup;
        try {
            pickup = Instant.parse(pickupTime);
        } catch (Exception e) {
            return Map.of("error", "pickup_time must be ISO 8601 (e.g. 2026-05-03T15:30:00Z).");
        }
        if (pickup.isBefore(Instant.now().plusSeconds(15 * 60))) {
            return Map.of("error", "pickup_time must be at least 15 minutes in the future.");
        }

        List<LineItemRequest> lineItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object varId = item.get("variation_id");
            Object qty   = item.get("quantity");
            if (varId == null || varId.toString().isBlank()) {
                return Map.of("error", "Each item must have a 'variation_id' from get_menu.");
            }
            int quantity = 1;
            if (qty != null) {
                try { quantity = Integer.parseInt(qty.toString()); }
                catch (NumberFormatException e) {
                    return Map.of("error", "quantity must be an integer.");
                }
            }
            if (quantity < 1) return Map.of("error", "quantity must be at least 1.");
            lineItems.add(new LineItemRequest(varId.toString(), quantity));
        }

        try {
            OrderResult result = currentClient().createOrder(customerName, lineItems, pickupTime);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("order_id",             result.orderId);
            out.put("estimated_ready_time", result.pickupAt);
            out.put("order_total",          result.totalAmount);
            return out;
        } catch (Exception e) {
            return Map.of("error", "Order creation failed: " + e.getMessage());
        }
    }

    public Map<String, Object> getWaitTime() {
        WaitEstimate estimate = currentClient().estimateWaitMinutes();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active_orders_ahead", estimate.activeOrdersAhead);
        result.put("estimated_wait_minutes", estimate.estimatedWaitMinutes);
        result.put("confidence", "approximate");
        result.put("note",
                "Estimated by summing prep times of active pickup orders. " +
                        "Actual wait depends on kitchen capacity and may differ."
        );
        return result;
    }
}
