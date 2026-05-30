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

import com.squareup.square.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Square POS system provider.
 * Wraps SquareFoodTruckClient to implement the PosProvider interface.
 */
public class SquarePosProvider implements PosProvider {

    private static final Logger log = LoggerFactory.getLogger(SquarePosProvider.class);

    private final SquareFoodTruckClient squareClient;
    private final String locationId;

    public SquarePosProvider(String accessToken, String locationId, String environment) {
        this.locationId = locationId;
        this.squareClient = new SquareFoodTruckClient(
                accessToken,
                locationId,
                "production".equalsIgnoreCase(environment) ? Environment.PRODUCTION : Environment.SANDBOX
        );
    }

    @Override
    public List<MenuItem> getMenu(boolean refresh) throws Exception {
        var squareItems = squareClient.getMenu(refresh);
        List<MenuItem> items = new ArrayList<>();

        for (SquareFoodTruckClient.MenuItem squareItem : squareItems) {
            long priceMoney = 0;
            if (squareItem.priceMoney != null && squareItem.priceMoney.getAmount() != null) {
                priceMoney = squareItem.priceMoney.getAmount();
            }
            Map<String, Boolean> dietaryPrefs = new HashMap<>();
            if (squareItem.dietaryPreferences != null) {
                for (String pref : squareItem.dietaryPreferences) {
                    dietaryPrefs.put(pref, true);
                }
            }
            MenuItem item = new MenuItem(
                    squareItem.variationId,
                    squareItem.name,
                    squareItem.description,
                    priceMoney,
                    squareItem.ingredients,
                    dietaryPrefs
            );
            items.add(item);
        }
        return items;
    }

    @Override
    public CreateOrderResult createOrder(String customerName, List<Map<String, Object>> items, String pickupTime) throws Exception {
        var squareItems = new ArrayList<SquareFoodTruckClient.LineItemRequest>();
        for (Map<String, Object> item : items) {
            String variationId = (String) item.get("variation_id");
            int quantity = item.get("quantity") instanceof Integer
                    ? (Integer) item.get("quantity")
                    : Integer.parseInt(item.get("quantity").toString());
            squareItems.add(new SquareFoodTruckClient.LineItemRequest(variationId, quantity));
        }

        var result = squareClient.createOrder(customerName, squareItems, pickupTime);
        // Parse totalAmount string (e.g., "12.50") to long cents (1250)
        long totalMoney = 0;
        try {
            if (result.totalAmount != null) {
                totalMoney = (long) (Double.parseDouble(result.totalAmount) * 100);
            }
        } catch (NumberFormatException e) {
            log.warn("Could not parse total amount: {}", result.totalAmount);
        }
        return new CreateOrderResult(result.orderId, totalMoney, locationId);
    }

    @Override
    public String getOrderStatus(String orderId) throws Exception {
        // Square doesn't provide a simple status API in this version
        // For now, return "unknown" — would need Orders API integration
        return "unknown";
    }

    @Override
    public int getWaitTimeMinutes() throws Exception {
        var estimate = squareClient.estimateWaitMinutes();
        return estimate.estimatedWaitMinutes;
    }
}
