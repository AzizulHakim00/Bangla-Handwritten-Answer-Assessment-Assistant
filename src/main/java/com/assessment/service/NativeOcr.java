package com.assessment.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

final class NativeOcr {
    private NativeOcr() {}

    static String read(String command, BufferedImage image, long deadline)
        throws IOException, InterruptedException {
        Path temp = Files.createTempDirectory("answer-ocr-");
        Process process = null;
        try {
            Path input = temp.resolve("input.png"), output = temp.resolve("result");
            ImageIO.write(image, "png", input.toFile());
            var builder = new ProcessBuilder(command, input.toString(), output.toString(), "-l", "Bengali", "--oem", "1", "--psm", "6",
                "-c", "tessedit_char_blacklist=ৰৱ")
                .directory(temp.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            var env = builder.environment();
            String path = env.getOrDefault("PATH", "/usr/bin:/bin"), tessdata = env.get("TESSDATA_PREFIX");
            env.clear();
            env.put("PATH", path);
            env.put("OMP_THREAD_LIMIT", "1");
            if (tessdata != null) env.put("TESSDATA_PREFIX", tessdata);
            process = builder.start();
            long seconds = Math.max(1, Math.min(45, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime())));
            if (!process.waitFor(seconds, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("OCR did not complete");
            Path result = Path.of(output + ".txt");
            if (Files.size(result) > 120_000) throw new IOException("OCR output too large");
            return Files.readString(result).strip();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            try (var files = Files.list(temp)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }
}
