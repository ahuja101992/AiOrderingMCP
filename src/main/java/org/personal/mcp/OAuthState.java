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

import java.util.Objects;

/**
 * Temporary OAuth authorization state.
 * Stored during the redirect-to-Square phase, retrieved during the callback.
 * TTL: 10 minutes. Prevents CSRF by validating the state param matches.
 */
public class OAuthState {
    public final String state;
    public final String truckName;
    public final String truckId;
    public final String environment;
    public final long expiresAt;

    private static final long TTL_MILLIS = 10 * 60 * 1000;

    public OAuthState(String state, String truckName, String truckId, String environment) {
        this.state       = Objects.requireNonNull(state);
        this.truckName   = Objects.requireNonNull(truckName);
        this.truckId     = Objects.requireNonNull(truckId);
        this.environment = environment;
        this.expiresAt   = System.currentTimeMillis() + TTL_MILLIS;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
