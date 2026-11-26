package com.assessment.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class PrivateData {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path keyPath;
    private final Map<Integer, byte[]> configuredKeys;
    private final int activeVersion;

    PrivateData(Path storage, String configuredKey, String versionsJson, int activeVersion) throws IOException {
        keyPath = storage.resolve(".encryption-key-v1");
        this.activeVersion = activeVersion;
        var keys = new HashMap<Integer, byte[]>();
        if (!configuredKey.isBlank()) keys.put(1, decode(configuredKey));
        if (!versionsJson.isBlank()) {
            var entries = new ObjectMapper().readValue(versionsJson, new TypeReference<Map<Integer, String>>() {});
            for (var entry : entries.entrySet()) {
                if (entry.getKey() < 1 || entry.getKey() > 255 || keys.putIfAbsent(entry.getKey(), decode(entry.getValue())) != null)
                    throw new IOException("Invalid or duplicate encryption key version");
            }
        }
        if (activeVersion != 1 && !keys.containsKey(activeVersion)) throw new IOException("Active encryption key version is missing");
        configuredKeys = Map.copyOf(keys);
    }

    private static byte[] decode(String value) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length == 32) return bytes;
        } catch (IllegalArgumentException ignored) {}
        throw new IOException("Encryption keys must contain 32 Base64-encoded bytes");
    }

    byte[] encrypt(byte[] plaintext, String context) throws IOException {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, context, activeVersion);
        byte[] payload = new byte[1 + nonce.length + ciphertext.length];
        payload[0] = (byte) activeVersion;
        System.arraycopy(nonce, 0, payload, 1, nonce.length);
        System.arraycopy(ciphertext, 0, payload, 13, ciphertext.length);
        return payload;
    }

    byte[] decrypt(byte[] payload, String context) throws IOException {
        if (payload.length < 29 || payload[0] == 0) throw new IOException("Invalid encrypted object");
        return crypt(Cipher.DECRYPT_MODE, java.util.Arrays.copyOfRange(payload, 13, payload.length),
            java.util.Arrays.copyOfRange(payload, 1, 13), context, Byte.toUnsignedInt(payload[0]));
    }

    private byte[] crypt(int mode, byte[] data, byte[] nonce, String context, int version) throws IOException {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key(version, mode == Cipher.ENCRYPT_MODE), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((version == 1 ? context : context + "|key-version-" + version).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IOException("Private object authentication failed", ex);
        }
    }

    private synchronized byte[] key(int version, boolean create) throws IOException {
        byte[] configured = configuredKeys.get(version);
        if (configured != null) return configured;
        if (version != 1) throw new IOException("Encryption key version is missing");
        if (!Files.exists(keyPath)) {
            if (!create) throw new IOException("Original encryption key is missing");
            byte[] candidate = new byte[32];
            RANDOM.nextBytes(candidate);
            writeNewSecret(keyPath, candidate);
        }
        byte[] stored = Files.readAllBytes(keyPath);
        if (stored.length != 32) throw new IOException("Invalid stored encryption key");
        return stored;
    }

    static void writeNewSecret(Path path, byte[] data) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".secret-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) {}
            Files.write(temporary, data);
            try { Files.createLink(path, temporary); }
            catch (FileAlreadyExistsException ignored) { /* A concurrent initializer installed the key. */ }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
