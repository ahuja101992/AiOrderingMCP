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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for Square access tokens stored in trucks.json.
 *
 * <p>Stored ciphertext format: {@code enc:<base64(12-byte-IV || ciphertext+tag)>}
 * The {@code enc:} prefix lets us distinguish encrypted from legacy plain-text tokens
 * so that existing trucks.json files without encryption continue to load.
 *
 * <p>Usage: set {@code TOKEN_ENCRYPTION_KEY} to a base64-encoded 32-byte key.
 * Generate one with: {@code openssl rand -base64 32}
 */
public class TokenEncryption {

    private static final Logger log = LoggerFactory.getLogger(TokenEncryption.class);

    static final String ENCRYPTED_PREFIX = "enc:";
    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_BYTES   = 12;
    private static final int    TAG_BITS   = 128;

    private final SecretKeySpec keySpec;

    public TokenEncryption(byte[] keyBytes) {
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                "TOKEN_ENCRYPTION_KEY must decode to 32 bytes (256 bits); got " + keyBytes.length);
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    /** Returns a {@code TokenEncryption} from the {@code TOKEN_ENCRYPTION_KEY} env var, or {@code null} if unset. */
    public static TokenEncryption fromEnv() {
        String val = System.getenv("TOKEN_ENCRYPTION_KEY");
        if (val == null || val.isBlank()) {
            log.warn("TOKEN_ENCRYPTION_KEY is not set — tokens will be stored in plain text. " +
                     "Generate a key with: openssl rand -base64 32");
            return null;
        }
        try {
            return new TokenEncryption(Base64.getDecoder().decode(val.trim()));
        } catch (Exception e) {
            throw new IllegalStateException("TOKEN_ENCRYPTION_KEY is not valid base64: " + e.getMessage(), e);
        }
    }

    /** Returns true if {@code value} was produced by {@link #encrypt}. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENCRYPTED_PREFIX);
    }

    /**
     * Encrypts {@code plaintext} and returns an {@code enc:...} string safe for storage.
     * Each call produces a unique ciphertext due to a fresh random IV.
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv,         0, combined, 0,       IV_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_BYTES, ciphertext.length);

            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Token encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}.
     * If {@code value} does not start with {@code enc:}, it is returned as-is
     * (backward compatibility with pre-encryption trucks.json files).
     */
    public String decrypt(String value) {
        if (!isEncrypted(value)) return value;
        try {
            byte[] combined  = Base64.getDecoder().decode(value.substring(ENCRYPTED_PREFIX.length()));
            byte[] iv         = new byte[IV_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0,       iv,         0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Token decryption failed — wrong key or corrupted data", e);
        }
    }
}