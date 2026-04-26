package org.personal.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.personal.mcp.TruckIdFilter.extractTruckId;

class TruckIdFilterTest {

    @Test
    void extractsBasic() {
        assertEquals("joes_tacos", extractTruckId("/truck/joes_tacos/mcp", ""));
    }

    @Test
    void extractsWithTrailingSegments() {
        assertEquals("joes_tacos", extractTruckId("/truck/joes_tacos/mcp/messages", ""));
    }

    @Test
    void stripsContextPath() {
        assertEquals("demo", extractTruckId("/api/truck/demo/mcp", "/api"));
    }

    @Test
    void rejectsMissingMcpSegment() {
        assertNull(extractTruckId("/truck/joes_tacos/something_else", ""));
    }

    @Test
    void rejectsMissingTruckId() {
        assertNull(extractTruckId("/truck//mcp", ""));
    }

    @Test
    void rejectsWrongPrefix() {
        assertNull(extractTruckId("/health", ""));
        assertNull(extractTruckId("/", ""));
    }

    @Test
    void rejectsTooShortPath() {
        assertNull(extractTruckId("/truck/joes_tacos", ""));
    }

    @Test
    void handlesNull() {
        assertNull(extractTruckId(null, ""));
    }
}
