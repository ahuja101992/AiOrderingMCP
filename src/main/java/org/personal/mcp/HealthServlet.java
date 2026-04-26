package org.personal.mcp;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Trivial liveness endpoint at {@code /health}. Useful for load balancer
 * health checks and for confirming the process is alive without exercising
 * the MCP layer or any Square credentials.
 */
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"status\":\"ok\"}");
    }
}
