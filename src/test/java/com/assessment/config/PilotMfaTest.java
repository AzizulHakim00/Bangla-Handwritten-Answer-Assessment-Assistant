package com.assessment.config;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PilotMfaTest {
    @Test void matchesRfc6238Sha1TestVectors() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals("94287082", PilotMfa.generate(secret, 59 / 30, 8));
        assertEquals("07081804", PilotMfa.generate(secret, 1111111109L / 30, 8));
        assertEquals("14050471", PilotMfa.generate(secret, 1111111111L / 30, 8));
    }
}
