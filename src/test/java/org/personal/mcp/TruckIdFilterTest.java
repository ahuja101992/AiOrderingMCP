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
