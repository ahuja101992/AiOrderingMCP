package org.personal.mcp;

import org.personal.mcp.SquareFoodTruckClient.MenuItem;
import org.personal.mcp.SquareFoodTruckClient.WaitEstimate;

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