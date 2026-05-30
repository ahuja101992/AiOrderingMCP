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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores order history per truck. Persisted to JSON file for durability.
 * Format: { "truck_id": [ { "order_id", "customer_name", "items", "pickup_time", "created_at", "status" }, ... ] }
 */
public class OrderHistory {

    private static final Logger log = LoggerFactory.getLogger(OrderHistory.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final Path storePath;
    private final Map<String, List<OrderRecord>> ordersByTruck = new ConcurrentHashMap<>();

    public OrderHistory(String storePath) {
        this.storePath = Path.of(storePath != null && !storePath.isBlank() ? storePath : "orders.json");
        load();
    }

    public void recordOrder(String truckId, String orderId, String customerName, List<Map<String, Object>> items, String pickupTime) {
        OrderRecord record = new OrderRecord(
                orderId, customerName, items, pickupTime, System.currentTimeMillis(), "pending"
        );
        ordersByTruck.computeIfAbsent(truckId, k -> new ArrayList<>()).add(record);
        save();
        log.info("Recorded order {} for truck '{}', customer '{}'", orderId, truckId, customerName);
    }

    public Optional<OrderRecord> getOrder(String truckId, String orderId) {
        List<OrderRecord> orders = ordersByTruck.getOrDefault(truckId, List.of());
        return orders.stream().filter(o -> o.orderId.equals(orderId)).findFirst();
    }

    public List<OrderRecord> getOrdersForTruck(String truckId) {
        return new ArrayList<>(ordersByTruck.getOrDefault(truckId, List.of()));
    }

    public void updateOrderStatus(String truckId, String orderId, String newStatus) {
        List<OrderRecord> orders = ordersByTruck.get(truckId);
        if (orders != null) {
            for (OrderRecord record : orders) {
                if (record.orderId.equals(orderId)) {
                    record.status = newStatus;
                    save();
                    log.info("Updated order {} status to '{}'", orderId, newStatus);
                    return;
                }
            }
        }
    }

    private void load() {
        if (!Files.exists(storePath)) {
            return;
        }
        try {
            JsonNode root = json.readTree(Files.readAllBytes(storePath));
            if (!root.isObject()) return;

            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String truckId = entry.getKey();
                JsonNode ordersNode = entry.getValue();
                if (!ordersNode.isArray()) continue;

                List<OrderRecord> orders = new ArrayList<>();
                for (JsonNode orderNode : ordersNode) {
                    OrderRecord record = new OrderRecord(
                            orderNode.get("order_id").asText(),
                            orderNode.get("customer_name").asText(),
                            json.convertValue(orderNode.get("items"), List.class),
                            orderNode.get("pickup_time").asText(),
                            orderNode.get("created_at").asLong(),
                            orderNode.get("status").asText()
                    );
                    orders.add(record);
                }
                ordersByTruck.put(truckId, orders);
            }
            log.info("Loaded order history from {}", storePath);
        } catch (IOException e) {
            log.warn("Could not load order history: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            ObjectNode root = json.createObjectNode();
            for (Map.Entry<String, List<OrderRecord>> entry : ordersByTruck.entrySet()) {
                ArrayNode ordersArray = root.putArray(entry.getKey());
                for (OrderRecord record : entry.getValue()) {
                    ObjectNode orderNode = ordersArray.addObject();
                    orderNode.put("order_id", record.orderId);
                    orderNode.put("customer_name", record.customerName);
                    orderNode.putPOJO("items", record.items);
                    orderNode.put("pickup_time", record.pickupTime);
                    orderNode.put("created_at", record.createdAt);
                    orderNode.put("status", record.status);
                }
            }
            Files.writeString(storePath, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            log.error("Failed to save order history: {}", e.getMessage());
        }
    }

    public static class OrderRecord {
        public final String orderId;
        public final String customerName;
        public final List<Map<String, Object>> items;
        public final String pickupTime;
        public final long createdAt;
        public String status;

        public OrderRecord(String orderId, String customerName, List<Map<String, Object>> items,
                          String pickupTime, long createdAt, String status) {
            this.orderId = orderId;
            this.customerName = customerName;
            this.items = items;
            this.pickupTime = pickupTime;
            this.createdAt = createdAt;
            this.status = status;
        }
    }
}
