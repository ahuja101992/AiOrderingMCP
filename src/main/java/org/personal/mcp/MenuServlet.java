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
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/menu/{truck_id}
 *
 * Returns the truck's menu as JSON for the order modal.
 * Uses the same cached data as the get_menu MCP tool.
 *
 * Response:
 * { "truck_id": "joes_tacos", "items": [
 *     { "id": "...", "variation_id": "...", "name": "...", "price": "12.00 USD" }, ...
 * ]}
 */
public class MenuServlet extends HttpServlet {

    private static final ObjectMapper json = new ObjectMapper();

    private final TruckRegistry registry;
    private final SquareClientFactory clientFactory;

    public MenuServlet(TruckRegistry registry, SquareClientFactory clientFactory) {
        this.registry      = registry;
        this.clientFactory = clientFactory;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=utf-8");

        // Path: /api/menu/{truck_id}
        String path    = req.getPathInfo();
        String truckId = (path != null && path.length() > 1) ? path.substring(1) : null;

        if (truckId == null || truckId.isBlank() || truckId.contains("/")) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"truck_id required\"}");
            return;
        }

        if (registry.lookup(truckId).isEmpty()) {
            resp.setStatus(404);
            resp.getWriter().write("{\"error\":\"Unknown truck_id\"}");
            return;
        }

        try {
            SquareFoodTruckClient client = clientFactory.forTruck(truckId);
            List<SquareFoodTruckClient.MenuItem> items = client.getMenu(false);

            List<Map<String, Object>> serialized = new ArrayList<>(items.size());
            for (SquareFoodTruckClient.MenuItem item : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",           item.id);
                m.put("variation_id", item.variationId);
                m.put("name",         item.name);
                m.put("price",        item.formattedPrice());
                serialized.add(m);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("truck_id", truckId);
            result.put("items",    serialized);

            resp.setStatus(200);
            resp.getWriter().write(json.writeValueAsString(result));

        } catch (Exception e) {
            resp.setStatus(502);
            resp.getWriter().write("{\"error\":\"Failed to fetch menu\"}");
        }
    }
}
