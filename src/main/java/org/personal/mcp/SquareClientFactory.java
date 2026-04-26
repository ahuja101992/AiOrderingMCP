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

import com.squareup.square.Environment;
import org.personal.mcp.TruckRegistry.TruckCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily produces and caches one {@link SquareFoodTruckClient} per truck.
 *
 * <p>Each {@code SquareFoodTruckClient} holds its own menu cache, so we want
 * exactly one instance per truck across the lifetime of the server — otherwise
 * every request would re-pull the menu from Square and we'd burn rate limit.
 *
 * <p>Thread-safety: backed by {@link ConcurrentHashMap}, and {@code computeIfAbsent}
 * guarantees the factory function runs at most once per key even under
 * concurrent requests for the same truck.
 */
public class SquareClientFactory {

    private static final Logger log = LoggerFactory.getLogger(SquareClientFactory.class);

    private final TruckRegistry registry;
    private final Map<String, SquareFoodTruckClient> clientsByTruckId = new ConcurrentHashMap<>();

    public SquareClientFactory(TruckRegistry registry) {
        this.registry = registry;
    }

    public SquareFoodTruckClient forTruck(String truckId) {
        return clientsByTruckId.computeIfAbsent(truckId, this::buildClient);
    }

    private SquareFoodTruckClient buildClient(String truckId) {
        TruckCredentials creds = registry.lookup(truckId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Unknown truck_id: '" + truckId + "'. Check the URL or registry config."
                ));
        log.info("Building Square client for truck '{}' (env={})", truckId, creds.environment);
        return new SquareFoodTruckClient(
                creds.accessToken,
                creds.locationId,
                "production".equalsIgnoreCase(creds.environment) ? Environment.PRODUCTION : Environment.SANDBOX
        );
    }
}
