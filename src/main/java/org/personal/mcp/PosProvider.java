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

import java.util.List;
import java.util.Map;

/**
 * Abstraction for POS system integrations (Square, Toast, Lightspeed).
 * Each POS provider implements this interface to expose menu, order, and status operations.
 */
public interface PosProvider {

    /**
     * Menu item with details for ordering.
     */
    class MenuItem {
        public final String variationId;
        public final String name;
        public final String description;
        public final long priceMoney;
        public final List<String> ingredients;
        public final Map<String, Boolean> dietaryPreferences;

        public MenuItem(String variationId, String name, String description, long priceMoney,
                       List<String> ingredients, Map<String, Boolean> dietaryPreferences) {
            this.variationId = variationId;
            this.name = name;
            this.description = description;
            this.priceMoney = priceMoney;
            this.ingredients = ingredients;
            this.dietaryPreferences = dietaryPreferences;
        }
    }

    /**
     * Fetch the full menu from the POS.
     * @param refresh if true, bypass cache and fetch fresh from POS
     * @return list of menu items with all details
     */
    List<MenuItem> getMenu(boolean refresh) throws Exception;

    /**
     * Create an order in the POS system.
     * @param customerName name for the order
     * @param items list of items with variation_id and quantity
     * @param pickupTime ISO 8601 UTC timestamp for pickup
     * @return result with orderId and other details
     */
    CreateOrderResult createOrder(String customerName, List<Map<String, Object>> items, String pickupTime) throws Exception;

    /**
     * Check the current status of an order.
     */
    String getOrderStatus(String orderId) throws Exception;

    /**
     * Estimate wait time for a new order.
     */
    int getWaitTimeMinutes() throws Exception;

    class CreateOrderResult {
        public final String orderId;
        public final long totalMoney;
        public final String locationId;

        public CreateOrderResult(String orderId, long totalMoney, String locationId) {
            this.orderId = orderId;
            this.totalMoney = totalMoney;
            this.locationId = locationId;
        }
    }
}
