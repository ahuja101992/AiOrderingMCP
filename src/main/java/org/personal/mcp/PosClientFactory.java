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

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory that provides PosProvider instances per truck.
 * Handles caching and token refresh logic.
 */
public class PosClientFactory {

    private static final Logger log = LoggerFactory.getLogger(PosClientFactory.class);

    private final TruckRegistry registry;
    private final Map<String, PosProvider> providersByTruckId = new ConcurrentHashMap<>();

    public PosClientFactory(TruckRegistry registry) {
        this.registry = registry;
    }

    public PosProvider forTruck(String truckId) {
        return providersByTruckId.computeIfAbsent(truckId, this::buildProvider);
    }

    private PosProvider buildProvider(String truckId) {
        TruckRegistry.TruckCredentials creds = registry.lookup(truckId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Unknown truck_id: '" + truckId + "'. Check the URL or registry config."
                ));

        PosProviderFactory.PosType posType = PosProviderFactory.PosType.fromString(creds.posType);
        log.info("Building {} POS provider for truck '{}' (env={})", posType.getId(), truckId, creds.environment);

        return PosProviderFactory.createProvider(posType, creds);
    }
}
