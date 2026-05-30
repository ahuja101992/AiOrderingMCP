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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * GET /api/orders/{truckId} - get all orders for a truck
 * GET /api/orders/{truckId}/{orderId} - get a specific order
 */
public class OrderHistoryServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryServlet.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final OrderHistory orderHistory;

    public OrderHistoryServlet(OrderHistory orderHistory) {
        this.orderHistory = orderHistory;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=utf-8");

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            error(resp, 400, "Invalid request");
            return;
        }

        String[] parts = pathInfo.split("/");
        if (parts.length < 2) {
            error(resp, 400, "Invalid request");
            return;
        }

        String truckId = parts[1];
        if (truckId.isEmpty()) {
            error(resp, 400, "truck_id required");
            return;
        }

        // GET /api/orders/{truckId}/{orderId}
        if (parts.length >= 3 && !parts[2].isEmpty()) {
            String orderId = parts[2];
            Optional<OrderHistory.OrderRecord> record = orderHistory.getOrder(truckId, orderId);
            if (record.isEmpty()) {
                error(resp, 404, "Order not found");
                return;
            }

            ObjectNode out = recordToJson(record.get());
            resp.setStatus(200);
            resp.getWriter().write(json.writeValueAsString(out));
        }
        // GET /api/orders/{truckId}
        else {
            List<OrderHistory.OrderRecord> records = orderHistory.getOrdersForTruck(truckId);
            ArrayNode array = json.createArrayNode();
            for (OrderHistory.OrderRecord record : records) {
                array.add(recordToJson(record));
            }

            ObjectNode out = json.createObjectNode();
            out.set("orders", array);
            resp.setStatus(200);
            resp.getWriter().write(json.writeValueAsString(out));
        }
    }

    private ObjectNode recordToJson(OrderHistory.OrderRecord record) {
        ObjectNode node = json.createObjectNode();
        node.put("order_id", record.orderId);
        node.put("customer_name", record.customerName);
        node.putPOJO("items", record.items);
        node.put("pickup_time", record.pickupTime);
        node.put("created_at", Instant.ofEpochMilli(record.createdAt).toString());
        node.put("status", record.status);
        return node;
    }

    private static void error(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.getWriter().write("{\"error\":\"" + msg.replace("\"", "'") + "\"}");
    }
}
