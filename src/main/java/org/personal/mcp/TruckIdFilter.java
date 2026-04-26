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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Pulls the truck_id segment out of the request URL and stashes it in
 * {@link RequestContext} for the rest of the request lifecycle.
 *
 * <p>URL pattern: {@code /truck/{truck_id}/mcp[...]}. The path is parsed
 * directly rather than relying on servlet path parameters because the MCP
 * servlet itself maps to a wildcard pattern under each truck.
 *
 * <p>If the URL doesn't match the expected shape, or the truck_id isn't in the
 * registry, we respond with a 404 and don't forward to the MCP servlet at all.
 * This is what we want — better to fail at the edge than to let the MCP layer
 * run without a valid truck context.
 */
public class TruckIdFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TruckIdFilter.class);

    private final TruckRegistry registry;

    public TruckIdFilter(TruckRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (!(req instanceof HttpServletRequest httpReq) || !(res instanceof HttpServletResponse httpRes)) {
            chain.doFilter(req, res);
            return;
        }

        String truckId = extractTruckId(httpReq.getRequestURI(), httpReq.getContextPath());
        if (truckId == null) {
            httpRes.sendError(404, "URL must be of the form /truck/{truck_id}/mcp");
            return;
        }
        if (registry.lookup(truckId).isEmpty()) {
            log.warn("Request for unknown truck_id '{}' from {}", truckId, httpReq.getRemoteAddr());
            httpRes.sendError(404, "Unknown truck: " + truckId);
            return;
        }

        try {
            RequestContext.setCurrentTruckId(truckId);
            chain.doFilter(req, res);
        } finally {
            // Critical: Jetty pools threads, so leaking the ThreadLocal would
            // make the next request on this thread think it's for the previous truck.
            RequestContext.clear();
        }
    }

    /**
     * Extract the truck_id from a URL path of the form /truck/{truck_id}/mcp[...].
     * Returns null if the path doesn't match.
     */
    static String extractTruckId(String requestUri, String contextPath) {
        if (requestUri == null) return null;
        String path = requestUri;
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        // Expect /truck/{id}/mcp[...]
        if (!path.startsWith("/truck/")) return null;
        String afterPrefix = path.substring("/truck/".length());
        int slash = afterPrefix.indexOf('/');
        if (slash <= 0) return null;
        String truckId = afterPrefix.substring(0, slash);
        // Sanity: the next segment must start with "mcp"
        String rest = afterPrefix.substring(slash);
        if (!rest.startsWith("/mcp")) return null;
        return truckId;
    }
}
