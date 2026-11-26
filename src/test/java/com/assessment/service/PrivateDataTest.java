package com.assessment.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrivateDataTest {
    @TempDir Path directory;

    @Test void keyRotationPreservesExistingCiphertextAndBindsTenantContext() throws Exception {
        var first = new PrivateData(directory, "", "", 1);
        byte[] text = "protected assessment data".getBytes(StandardCharsets.UTF_8);
        String context = "school|SCR-1|1|original";
        byte[] old = first.encrypt(text, context);

        byte[] secondKey = new byte[32];
        new SecureRandom().nextBytes(secondKey);
        String registry = "{\"2\":\"" + Base64.getEncoder().encodeToString(secondKey) + "\"}";
        var rotated = new PrivateData(directory, "", registry, 2);
        byte[] latest = rotated.encrypt(text, context);

        assertEquals(1, old[0]);
        assertEquals(2, latest[0]);
        assertArrayEquals(text, rotated.decrypt(old, context));
        assertArrayEquals(text, rotated.decrypt(latest, context));
        assertThrows(IOException.class, () -> rotated.decrypt(latest, "other|SCR-1|1|original"));
        assertThrows(IOException.class, () -> first.decrypt(latest, context));
    }
}
