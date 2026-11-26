package com.assessment.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactSignerTest {
    @TempDir Path directory;

    @Test void verificationDistinguishesAlteredArtifactsAndUnknownOrRevokedIssuers() throws Exception {
        var signer = new ArtifactSigner(new DocumentService(directory.toString()));
        byte[] artifact = "rubric source".getBytes(StandardCharsets.UTF_8);
        var seal = signer.sign("school", "RUBRIC", artifact, Map.of("revisionId", "RUB-1"));
        byte[] manifest = seal.manifest().getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getDecoder().decode(seal.signature());
        assertEquals("RUB-1", signer.verify("school", artifact, manifest, signature).path("revisionId").asText());
        assertEquals("UNKNOWN_KEY", assertThrows(ArtifactSigner.VerificationException.class,
            () -> signer.verify("other", artifact, manifest, signature)).getMessage());
        assertEquals("ALTERED", assertThrows(ArtifactSigner.VerificationException.class,
            () -> signer.verify("school", new byte[]{0}, manifest, signature)).getMessage());
        Files.createFile(directory.resolve(".signing-school").resolve(seal.keyId() + ".revoked"));
        assertEquals("REVOKED_KEY", assertThrows(ArtifactSigner.VerificationException.class,
            () -> signer.verify("school", artifact, manifest, signature)).getMessage());
    }
}
