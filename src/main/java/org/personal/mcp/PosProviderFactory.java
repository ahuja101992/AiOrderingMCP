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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating PosProvider instances based on POS type.
 */
public class PosProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(PosProviderFactory.class);

    public enum PosType {
        SQUARE("square"),
        TOAST("toast"),
        LIGHTSPEED("lightspeed");

        private final String id;

        PosType(String id) {
            this.id = id;
        }

        public static PosType fromString(String s) {
            if (s == null) return SQUARE; // default
            return switch (s.toLowerCase()) {
                case "toast" -> TOAST;
                case "lightspeed" -> LIGHTSPEED;
                default -> SQUARE;
            };
        }

        public String getId() {
            return id;
        }
    }

    public static PosProvider createProvider(PosType posType, TruckRegistry.TruckCredentials creds) {
        return switch (posType) {
            case SQUARE -> {
                log.debug("Creating Square POS provider");
                yield new SquarePosProvider(creds.accessToken, creds.locationId, creds.environment);
            }
            case TOAST -> {
                log.debug("Creating Toast POS provider");
                yield new ToastPosProvider(creds.accessToken, creds.locationId, creds.environment);
            }
            case LIGHTSPEED -> {
                log.debug("Creating Lightspeed POS provider");
                // TODO: implement when ready
                throw new UnsupportedOperationException("Lightspeed provider not yet implemented");
            }
        };
    }
}
