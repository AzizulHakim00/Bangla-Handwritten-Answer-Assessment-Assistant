package com.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.springframework.stereotype.Service;

@Service
public class ArtifactSigner {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Path storage;

    public ArtifactSigner(DocumentService documents) { this.storage = documents.storage; }

    public record Seal(String manifest, String signature, String publicKey, String keyId) {}

    public Seal sign(String tenant, String purpose, byte[] artifact, Map<String, Object> references) throws Exception {
        var keys = activeKey(tenant);
        var factory = KeyFactory.getInstance("Ed25519");
        byte[] publicBytes = decode(keys.get("public").asText());
        var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(decode(keys.get("private").asText())));
        String keyId = ReportService.hex(publicBytes);
        var manifest = new LinkedHashMap<>(references);
        manifest.put("format", switch (purpose) {
            case "MARK_SHEET" -> "assessment-report-v2";
            case "MARK_APPROVAL" -> "assessment-approval-v1";
            default -> "assessment-rubric-v1";
        });
        manifest.put("tenant", tenant);
        manifest.put("purpose", purpose);
        manifest.put("issuer", tenant);
        manifest.put("keyId", keyId);
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("artifactSha256", ReportService.hex(artifact));
        if (purpose.equals("MARK_SHEET")) manifest.put("pdfSha256", manifest.get("artifactSha256"));
        byte[] canonical = JSON.writeValueAsBytes(manifest);
        var signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(canonical);
        return new Seal(new String(canonical, StandardCharsets.UTF_8), Base64.getEncoder().encodeToString(signature.sign()),
            "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(publicBytes)
                + "\n-----END PUBLIC KEY-----\n", keyId);
    }

    public JsonNode verify(String tenant, byte[] artifact, byte[] manifestBytes, byte[] signature) throws Exception {
        var manifest = JSON.readTree(manifestBytes);
        String keyId = manifest.path("keyId").asText();
        if (!tenant.equals(manifest.path("tenant").asText()) || !tenant.equals(manifest.path("issuer").asText())
            || !keyId.matches("[0-9a-f]{64}")) throw new VerificationException("UNKNOWN_KEY");

        Path registry = registry(tenant);
        if (Files.exists(registry.resolve(keyId + ".revoked"))) throw new VerificationException("REVOKED_KEY");
        Path archived = registry.resolve(keyId + ".pub");
        byte[] encoded;
        if (Files.exists(archived)) {
            encoded = Files.readAllBytes(archived);
        } else {
            Path active = activePath(tenant);
            if (!Files.exists(active)) throw new VerificationException("UNKNOWN_KEY");
            encoded = decode(JSON.readTree(active.toFile()).path("public").asText());
        }
        if (!ReportService.hex(encoded).equals(keyId)) throw new VerificationException("UNKNOWN_KEY");
        var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        var check = Signature.getInstance("Ed25519");
        check.initVerify(key);
        check.update(manifestBytes);
        if (signature.length != 64 || !check.verify(signature) ||
            !ReportService.hex(artifact).equals(manifest.path("artifactSha256").asText())) {
            throw new VerificationException("ALTERED");
        }
        return manifest;
    }

    private synchronized JsonNode activeKey(String tenant) throws Exception {
        Path active = activePath(tenant);
        if (!Files.exists(active)) {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            PrivateData.writeNewSecret(active, JSON.writeValueAsBytes(Map.of(
                "private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                "public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))));
        }
        var keys = JSON.readTree(active.toFile());
        byte[] encoded = decode(keys.get("public").asText());
        String id = ReportService.hex(encoded);
        Path directory = Files.createDirectories(registry(tenant));
        if (Files.exists(directory.resolve(id + ".revoked"))) throw new IllegalStateException("Signing key has been revoked");
        PrivateData.writeNewSecret(directory.resolve(id + ".pub"), encoded);
        return keys;
    }

    private Path registry(String tenant) { return storage.resolve(".signing-" + tenant); }
    private Path activePath(String tenant) {
        return storage.resolve("school".equals(tenant) ? ".report-signing-keypair.json" : ".signing-key-" + tenant + ".json");
    }

    public static byte[] canonical(Object artifact) throws IOException { return JSON.writeValueAsBytes(artifact); }
    private static byte[] decode(String value) { return Base64.getDecoder().decode(value); }

    public static byte[] bundle(String name, byte[] artifact, Seal seal) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            entry(zip, name, artifact);
            entry(zip, "manifest.json", seal.manifest().getBytes(StandardCharsets.UTF_8));
            entry(zip, "manifest.sig", decode(seal.signature()));
            entry(zip, "public-key.pem", seal.publicKey().getBytes(StandardCharsets.US_ASCII));
        }
        return output.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    public static class VerificationException extends Exception {
        public VerificationException(String status) { super(status); }
    }
}
