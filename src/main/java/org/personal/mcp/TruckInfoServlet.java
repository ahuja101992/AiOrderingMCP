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
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * GET /api/trucks/{truck_id}/info
 *
 * Returns basic info about a truck for the chat page header.
 * Response: { "truck_id": "demo", "name": "Joe's Tacos", "exists": true }
 */
public class TruckInfoServlet extends HttpServlet {

    private static final ObjectMapper json = new ObjectMapper();
    private final TruckRegistry registry;

    public TruckInfoServlet(TruckRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        resp.setContentType("application/json; charset=utf-8");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        // Extract truck_id from path /api/trucks/{truck_id}/info
        String path = req.getPathInfo();
        if (path == null || path.equals("/")) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"truck_id required\"}");
            return;
        }

        // Path is like /joes_tacos/info or just /joes_tacos
        String[] parts = path.split("/");
        String truckId = parts.length > 1 ? parts[1] : null;

        if (truckId == null || truckId.isBlank()) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"truck_id required\"}");
            return;
        }

        ObjectNode result = json.createObjectNode();
        result.put("truck_id", truckId);
        result.put("exists", registry.lookup(truckId).isPresent());

        resp.setStatus(200);
        resp.getWriter().write(json.writeValueAsString(result));
    }
}
