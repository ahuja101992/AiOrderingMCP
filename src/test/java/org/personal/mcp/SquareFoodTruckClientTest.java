package org.personal.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.personal.mcp.SquareFoodTruckClient.parseIso8601Minutes;

class SquareFoodTruckClientTest {

    @Test
    void parsesMinutes() {
        assertEquals(15, parseIso8601Minutes("PT15M", 99));
    }

    @Test
    void parsesHours() {
        assertEquals(60, parseIso8601Minutes("PT1H", 99));
    }

    @Test
    void parsesHoursAndMinutes() {
        assertEquals(90, parseIso8601Minutes("PT1H30M", 99));
    }

    @Test
    void ignoresSeconds() {
        assertEquals(15, parseIso8601Minutes("PT15M30S", 99));
    }

    @Test
    void usesFallbackWhenNullOrBlank() {
        assertEquals(10, parseIso8601Minutes(null, 10));
        assertEquals(10, parseIso8601Minutes("", 10));
    }

    @Test
    void usesFallbackWhenInvalid() {
        assertEquals(7, parseIso8601Minutes("not a duration", 7));
    }
}
