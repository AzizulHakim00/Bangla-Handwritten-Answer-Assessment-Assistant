package com.assessment.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.CredentialsContainer;

public final class PilotMfa {
    private final Map<String, byte[]> secrets;
    private final Map<String, Long> lastUse = new ConcurrentHashMap<>();
    private final Clock clock;

    PilotMfa(Environment env, InstitutionAccounts accounts) throws IOException {
        this.clock = Clock.systemUTC();
        String raw = env.getProperty("ASSESSMENT_MFA_SECRETS_JSON", "");
        var configured = new ObjectMapper().readValue(raw.isBlank() ? "{}" : raw,
            new TypeReference<Map<String, String>>() {});
        var loaded = new HashMap<String, byte[]>();
        for (var entry : configured.entrySet()) {
            byte[] secret = Base64.getDecoder().decode(entry.getValue());
            if (secret.length < 20) throw new IllegalArgumentException("Authenticator secrets must have at least 20 bytes");
            loaded.put(entry.getKey(), secret);
        }
        secrets = Map.copyOf(loaded);
        if (env.getProperty("ASSESSMENT_PILOT", Boolean.class, false)) {
            for (var account : accounts.members()) {
                if (account.active() && !"MARKER".equals(account.role()) && !secrets.containsKey(account.email()))
                    throw new IllegalStateException("Configure authenticators for all active privileged pilot accounts");
            }
            if (!env.getProperty("ASSESSMENT_SECURE_COOKIES", Boolean.class, false))
                throw new IllegalStateException("Pilot mode requires secure session cookies");
        }
    }

    public boolean hasConfiguredAccounts() {
        return !secrets.isEmpty();
    }

    static final class Code implements CredentialsContainer, java.io.Serializable {
        private transient String value;
        Code(String value) { this.value = value; }
        @Override public void eraseCredentials() { value = null; }
    }

    void check(String username, Object details) {
        byte[] secret = secrets.get(username);
        if (secret == null) return;
        if (!(details instanceof Code code) || code.value == null || !code.value.matches("[0-9]{6}")) throw denied();
        byte[] supplied = code.value.getBytes(StandardCharsets.US_ASCII);
        long current = clock.instant().getEpochSecond() / 30;
        for (long step = current - 1; step <= current + 1; step++) {
            byte[] expected = generate(secret, step, 6).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(supplied, expected)) {
                if (!consume(username, step)) throw denied();
                return;
            }
        }
        throw denied();
    }

    private synchronized boolean consume(String username, long step) {
        if (step <= lastUse.getOrDefault(username, -1L)) return false;
        lastUse.put(username, step);
        return true;
    }

    // RFC 6238 uses the counter-based HOTP truncation with 30-second time steps.
    static String generate(byte[] secret, long step, int digits) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] bytes = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = bytes[bytes.length - 1] & 15;
            int binary = ByteBuffer.wrap(bytes, offset, Integer.BYTES).getInt() & Integer.MAX_VALUE;
            int base = digits == 6 ? 1_000_000 : 100_000_000;
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % base);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Authenticator is unavailable", ex);
        }
    }

    private static BadCredentialsException denied() { return new BadCredentialsException("Invalid credentials"); }
}
