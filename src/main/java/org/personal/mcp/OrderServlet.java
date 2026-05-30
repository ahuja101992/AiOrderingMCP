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
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * POST /api/order
 *
 * Body (JSON):
 * {
 *   "truck_id": "joes_tacos",
 *   "customer_name": "Alice",
 *   "items": [{"variation_id": "ABCDEF", "quantity": 2}],
 *   "pickup_time": "2026-05-03T15:30:00Z"
 * }
 *
 * Response (JSON):
 * { "order_id": "<uuid>", "estimated_ready_time": "...", "order_total": "12.00 USD" }
 *
 * The order is stored locally in pendingOrders; no Square API call is made here.
 * Square order creation happens atomically in PaymentServlet when the payment link is created.
 */
public class OrderServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(OrderServlet.class);
    private static final ObjectMapper json = new ObjectMapper();

    /** Shared with PaymentServlet. Keyed by server-generated order UUID. */
    final ConcurrentHashMap<String, PendingOrder> pendingOrders;
    private final TruckRegistry registry;
    private final SquareClientFactory clientFactory;

    public OrderServlet(TruckRegistry registry,
                        SquareClientFactory clientFactory,
                        ConcurrentHashMap<String, PendingOrder> pendingOrders) {
        this.registry       = registry;
        this.clientFactory  = clientFactory;
        this.pendingOrders  = pendingOrders;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=utf-8");

        String body = req.getReader().lines().collect(Collectors.joining());
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (Exception e) {
            error(resp, 400, "Invalid JSON body.");
            return;
        }

        String truckId      = text(node, "truck_id");
        String customerName = text(node, "customer_name");
        String pickupTime   = text(node, "pickup_time");
        JsonNode itemsNode  = node.get("items");

        if (truckId == null || customerName == null || pickupTime == null) {
            error(resp, 400, "truck_id, customer_name, and pickup_time are required.");
            return;
        }
        if (registry.lookup(truckId).isEmpty()) {
            error(resp, 404, "Unknown truck_id: " + truckId);
            return;
        }
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.size() == 0) {
            error(resp, 400, "items must be a non-empty array.");
            return;
        }

        Instant pickup;
        try {
            pickup = Instant.parse(pickupTime);
        } catch (Exception e) {
            error(resp, 400, "pickup_time must be ISO 8601 (e.g. 2026-05-03T15:30:00Z).");
            return;
        }
        if (pickup.isBefore(Instant.now().plusSeconds(15 * 60))) {
            error(resp, 422, "pickup_time must be at least 15 minutes in the future.");
            return;
        }

        List<SquareFoodTruckClient.LineItemRequest> lineItems = new ArrayList<>();
        SquareFoodTruckClient squareClient = clientFactory.forTruck(truckId);

        for (JsonNode item : itemsNode) {
            String variationId = item.hasNonNull("variation_id") ? item.get("variation_id").asText() : null;
            int quantity       = item.hasNonNull("quantity") ? item.get("quantity").asInt(1) : 1;

            if (variationId == null || variationId.isBlank()) {
                error(resp, 400, "Each item must include a 'variation_id'.");
                return;
            }
            if (quantity < 1) {
                error(resp, 400, "Item quantity must be at least 1.");
                return;
            }

            // Validate the variation ID exists in this truck's catalog
            boolean found = squareClient.getMenu(false).stream()
                    .anyMatch(m -> variationId.equals(m.variationId));
            if (!found) {
                error(resp, 422, "variation_id '" + variationId + "' not found in this truck's menu.");
                return;
            }

            lineItems.add(new SquareFoodTruckClient.LineItemRequest(variationId, quantity));
        }

        // Compute estimated total from cached menu prices
        String estimatedTotal = computeTotal(squareClient, lineItems);

        // Store the pending order locally (Square order created when payment link is made)
        String orderId = UUID.randomUUID().toString();
        PendingOrder pending = new PendingOrder(truckId, customerName, lineItems, pickupTime, estimatedTotal);
        pendingOrders.put(orderId, pending);

        log.info("Stored pending order {} for truck '{}' customer '{}'", orderId, truckId, customerName);

        ObjectNode result = json.createObjectNode();
        result.put("order_id",             orderId);
        result.put("estimated_ready_time", pickupTime);
        result.put("order_total",          estimatedTotal);

        resp.setStatus(200);
        resp.getWriter().write(json.writeValueAsString(result));
    }

    private String computeTotal(SquareFoodTruckClient client,
                                List<SquareFoodTruckClient.LineItemRequest> items) {
        long totalCents = 0;
        Map<String, SquareFoodTruckClient.MenuItem> byVariationId = new java.util.HashMap<>();
        for (SquareFoodTruckClient.MenuItem m : client.getMenu(false)) {
            if (m.variationId != null) byVariationId.put(m.variationId, m);
        }
        for (SquareFoodTruckClient.LineItemRequest item : items) {
            SquareFoodTruckClient.MenuItem m = byVariationId.get(item.variationId);
            if (m != null && m.priceMoney != null && m.priceMoney.getAmount() != null) {
                totalCents += m.priceMoney.getAmount() * item.quantity;
            }
        }
        return String.format("%.2f USD", totalCents / 100.0);
    }

    private static String text(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        String v = node.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }

    private static void error(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }

    /** Pending order data held in memory until the customer creates a payment link. */
    public static final class PendingOrder {
        public final String truckId;
        public final String customerName;
        public final List<SquareFoodTruckClient.LineItemRequest> items;
        public final String pickupTime;
        public final String estimatedTotal;

        PendingOrder(String truckId, String customerName,
                     List<SquareFoodTruckClient.LineItemRequest> items,
                     String pickupTime, String estimatedTotal) {
            this.truckId        = truckId;
            this.customerName   = customerName;
            this.items          = items;
            this.pickupTime     = pickupTime;
            this.estimatedTotal = estimatedTotal;
        }
    }
}