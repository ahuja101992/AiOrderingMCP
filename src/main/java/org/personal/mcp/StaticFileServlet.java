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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves static files from the classpath under /static/.
 *
 * URL /         → /static/index.html
 * URL /onboard  → /static/onboard.html
 * URL /chat/*   → /static/chat.html  (truck_id stays in browser URL)
 */
public class StaticFileServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        String path = req.getPathInfo();
        if (path == null) path = req.getServletPath();

        // Route to the right HTML file
        String resource;
        if (path == null || path.equals("/") || path.isEmpty()) {
            resource = "/static/index.html";
        } else if (path.startsWith("/chat")) {
            resource = "/static/chat.html";
        } else if (path.equals("/onboard")) {
            resource = "/static/onboard.html";
        } else {
            // Serve other static assets (CSS, JS, images) directly
            resource = "/static" + path;
        }

        InputStream in = getClass().getResourceAsStream(resource);
        if (in == null) {
            resp.sendError(404, "Not found: " + path);
            return;
        }

        resp.setStatus(200);
        resp.setContentType(contentType(resource));
        try (in) {
            in.transferTo(resp.getOutputStream());
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".js"))   return "application/javascript";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".png"))  return "image/png";
        return "application/octet-stream";
    }
}
