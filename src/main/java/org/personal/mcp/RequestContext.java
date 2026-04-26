package org.personal.mcp;

/**
 * Thread-local context for "which truck does the current request belong to?"
 *
 * <p>Why we need this: the MCP SDK's tool callback signature is
 * {@code (exchange, request) -> CallToolResult}. There's no place in that
 * contract to pass extra context like "this request came in on URL
 * /truck/joes_tacos/mcp." The transport layer (a servlet) sees the URL but the
 * tool handler doesn't.
 *
 * <p>Our solution: a servlet filter pulls the truck_id out of the URL path and
 * stores it in this ThreadLocal before MCP dispatches the request. The tool
 * handlers read it via {@link #currentTruckId()}.
 *
 * <p>Why ThreadLocal is safe here: each MCP HTTP request is handled on a single
 * thread end-to-end. The tool callbacks are synchronous (we're using
 * {@code McpSyncServer}), so no asynchronous handoff happens between the filter
 * setting the value and the tool reading it. The filter clears the ThreadLocal
 * in a {@code finally} block to prevent leaks across requests on the same
 * thread (Jetty pools threads).
 *
 * <p>What would force a redesign: if we ever switch to {@code McpAsyncServer}
 * with reactive tool callbacks, the work would jump threads and ThreadLocal
 * would be wrong. We'd then need Reactor's Context or a custom Subscriber
 * Context propagation. Documenting this here so future-us knows.
 */
public final class RequestContext {

    private static final ThreadLocal<String> CURRENT_TRUCK_ID = new ThreadLocal<>();

    private RequestContext() {}

    public static void setCurrentTruckId(String truckId) {
        CURRENT_TRUCK_ID.set(truckId);
    }

    public static String currentTruckId() {
        String id = CURRENT_TRUCK_ID.get();
        if (id == null) {
            throw new IllegalStateException(
                    "No truck_id in request context. The servlet filter should have set this " +
                            "from the URL path. If you see this in stdio mode you forgot to set it."
            );
        }
        return id;
    }

    public static void clear() {
        CURRENT_TRUCK_ID.remove();
    }
}
