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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * POST /api/create-checkout
 *
 * Body (JSON):
 * { "truck_id": "joes_tacos", "order_id": "<uuid from /api/order>" }
 *
 * Response (JSON):
 * { "checkout_url": "https://checkout.squareup.com/...", "square_order_id": "..." }
 *
 * Looks up the pending order from OrderServlet, then calls Square's Checkout API
 * to atomically create the Square order and a hosted payment link. The redirect URL
 * Square uses after payment sends the customer back to the chat page with
 * ?square_order_id=...&payment=complete appended.
 *
 * Card data never touches our server — Square's PCI-compliant hosted form handles it.
 */
public class PaymentServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(PaymentServlet.class);
    private static final ObjectMapper json = new ObjectMapper();

    private final ConcurrentHashMap<String, OrderServlet.PendingOrder> pendingOrders;
    private final SquareClientFactory clientFactory;

    public PaymentServlet(SquareClientFactory clientFactory,
                          ConcurrentHashMap<String, OrderServlet.PendingOrder> pendingOrders) {
        this.clientFactory = clientFactory;
        this.pendingOrders = pendingOrders;
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

        String truckId = text(node, "truck_id");
        String orderId = text(node, "order_id");

        if (truckId == null || orderId == null) {
            error(resp, 400, "truck_id and order_id are required.");
            return;
        }

        OrderServlet.PendingOrder pending = pendingOrders.get(orderId);
        if (pending == null) {
            error(resp, 404, "Order not found. It may have expired or already been paid.");
            return;
        }
        if (!pending.truckId.equals(truckId)) {
            error(resp, 403, "order_id does not belong to this truck.");
            return;
        }

        // Build redirect URL: after payment, Square sends the customer back to the chat
        String host = req.getScheme() + "://" + req.getHeader("host");
        String redirectUrl = host + "/chat/" + truckId + "?square_order_id=" +
                             java.net.URLEncoder.encode(orderId, "UTF-8") + "&payment=complete";

        try {
            SquareFoodTruckClient squareClient = clientFactory.forTruck(truckId);
            SquareFoodTruckClient.PaymentLinkResult result = squareClient.createPaymentLink(
                    pending.customerName, pending.items, pending.pickupTime, redirectUrl);

            // Remove the pending order now that the payment link is live
            pendingOrders.remove(orderId);
            log.info("Created payment link for order {} (truck '{}'), Square order_id={}",
                     orderId, truckId, result.squareOrderId);

            ObjectNode out = json.createObjectNode();
            out.put("checkout_url",    result.checkoutUrl);
            out.put("square_order_id", result.squareOrderId);

            resp.setStatus(200);
            resp.getWriter().write(json.writeValueAsString(out));

        } catch (Exception e) {
            log.error("Payment link creation failed for order {}", orderId, e);
            error(resp, 502, "Could not create payment link: " + sanitize(e.getMessage()));
        }
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

    /** Strip any token-like substrings from error messages before surfacing to client. */
    private static String sanitize(String msg) {
        if (msg == null) return "unknown error";
        return msg.replaceAll("EAA[A-Za-z0-9+/=]{10,}", "[redacted]");
    }
}