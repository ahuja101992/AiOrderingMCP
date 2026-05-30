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

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class TokenEncryptionTest {

    private static TokenEncryption enc() {
        // 32 zero bytes — valid 256-bit key for tests
        byte[] key = new byte[32];
        return new TokenEncryption(key);
    }

    @Test
    void roundTripProducesOriginalValue() {
        TokenEncryption e = enc();
        String plain = "EAAASANDBOX_TOKEN_ABC123";
        assertEquals(plain, e.decrypt(e.encrypt(plain)));
    }

    @Test
    void encryptedValueHasPrefix() {
        assertTrue(enc().encrypt("token").startsWith(TokenEncryption.ENCRYPTED_PREFIX));
    }

    @Test
    void eachEncryptionProducesUniqueCiphertext() {
        TokenEncryption e = enc();
        String a = e.encrypt("same");
        String b = e.encrypt("same");
        assertNotEquals(a, b); // different IV each time
    }

    @Test
    void decryptPassesThroughPlainText() {
        // Pre-encryption trucks.json entries (no enc: prefix) are returned as-is
        assertEquals("EAAAPLAIN", enc().decrypt("EAAAPLAIN"));
    }

    @Test
    void isEncryptedReturnsTrueOnlyForPrefixedValues() {
        assertTrue(TokenEncryption.isEncrypted("enc:abc"));
        assertFalse(TokenEncryption.isEncrypted("EAAAPLAIN"));
        assertFalse(TokenEncryption.isEncrypted(null));
    }

    @Test
    void wrongKeyThrowsOnDecrypt() {
        TokenEncryption e1 = enc();
        String ciphertext = e1.encrypt("secret");

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        TokenEncryption e2 = new TokenEncryption(otherKey);

        assertThrows(RuntimeException.class, () -> e2.decrypt(ciphertext));
    }

    @Test
    void rejectsKeyThatIsNot32Bytes() {
        assertThrows(IllegalArgumentException.class, () -> new TokenEncryption(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new TokenEncryption(new byte[64]));
    }

    @Test
    void handlesEmptyString() {
        TokenEncryption e = enc();
        assertEquals("", e.decrypt(e.encrypt("")));
    }
}
