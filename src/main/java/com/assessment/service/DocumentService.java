package com.assessment.service;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class DocumentService {
    private static final int MAX_PIXELS = 16_000_000;
    private static final int MAX_PAGES = 500;
    public final Path storage;
    private final PrivateData encryption;
    private final WorkerClient worker;

    public DocumentService(String path) throws IOException { this(path, "", "", 1, "", ""); }

    @Autowired
    public DocumentService(@Value("${assessment.storage:./data/scripts}") String path,
        @Value("${ASSESSMENT_ENCRYPTION_KEY:}") String key,
        @Value("${ASSESSMENT_ENCRYPTION_KEYS_JSON:}") String keyVersions,
        @Value("${ASSESSMENT_ENCRYPTION_ACTIVE_VERSION:1}") int activeKeyVersion,
        @Value("${ASSESSMENT_WORKER_URL:}") String workerUrl,
        @Value("${ASSESSMENT_WORKER_TOKEN:}") String workerToken) throws IOException {
        storage = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(storage);
        encryption = new PrivateData(storage, key, keyVersions, activeKeyVersion);
        worker = workerUrl.isBlank() ? null : new WorkerClient(workerUrl, workerToken);
    }

    public Path path(String id) {
        if (!id.matches("SCR-[a-zA-Z0-9-]+")) throw bad("Invalid document ID");
        return storage.resolve(id);
    }

    public Path store(Map<String, Object> script, byte[] plaintext) throws IOException {
        Path target = path(script.get("id").toString());
        byte[] enclosed = encryption.encrypt(plaintext, context(script));
        try {
            Files.createFile(target);
            Files.write(target, enclosed);
            return target;
        } catch (FileAlreadyExistsException ex) {
            throw ex;
        } catch (IOException ex) {
            Files.deleteIfExists(target);
            throw ex;
        }
    }

    public byte[] original(Map<String, Object> script) throws IOException {
        byte[] stored = Files.readAllBytes(path(script.get("id").toString()));
        byte[] bytes = script.containsKey("encryptionVersion") ? encryption.decrypt(stored, context(script)) : stored;
        if (script.containsKey("sha256") && !ReportService.hex(bytes).equals(script.get("sha256"))) {
            throw new IOException("Original document integrity check failed");
        }
        return bytes;
    }

    byte[] seal(byte[] bytes, String context) throws IOException { return encryption.encrypt(bytes, context); }
    byte[] unseal(byte[] bytes, String context) throws IOException { return encryption.decrypt(bytes, context); }

    private static String context(Map<String, Object> script) {
        return script.get("tenant") + "|" + script.get("id") + "|1|original";
    }

    public int pageCount(Map<String, Object> script) throws IOException {
        if (script.get("pages") instanceof Number count && count.intValue() > 0) return count.intValue();
        String mime = script.get("mime").toString();
        return "application/pdf".equals(mime) ? validate(original(script), mime) : 1;
    }

    public int validate(byte[] bytes, String mime) {
        if (worker == null) return localValidate(bytes, mime);
        try {
            return Integer.parseInt(new String(worker.call("validate", bytes, mime, 1, Map.of(), 25), java.nio.charset.StandardCharsets.US_ASCII));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document validation is unavailable", ex);
        }
    }

    static int localValidate(byte[] bytes, String mime) {
        try {
            if ("application/pdf".equals(mime)) {
                try (var pdf = Loader.loadPDF(bytes)) {
                    int count = pdf.getNumberOfPages();
                    if (pdf.isEncrypted() || count < 1 || count > MAX_PAGES) throw bad("Unsupported PDF");
                    for (var page : pdf.getPages()) validatePage(page);
                    return count;
                }
            }
            image(bytes);
            return 1;
        } catch (IOException | IllegalArgumentException ex) {
            throw bad("Document could not be decoded");
        }
    }

    public BufferedImage page(Map<String, Object> script, int page) throws IOException {
        byte[] bytes = original(script);
        String mime = script.get("mime").toString();
        if (worker == null) return localPage(bytes, mime, page);
        byte[] png = worker.call("page", bytes, mime, page, Map.of(), 30);
        return image(png);
    }

    String recognize(Map<String, Object> script, Map<String, Object> region, String command, long deadline)
        throws IOException, InterruptedException {
        int page = ((Number) region.get("page")).intValue();
        if (worker == null) return NativeOcr.read(command, crop(page(script, page), region), deadline);
        long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
        byte[] text = worker.call("ocr", original(script), script.get("mime").toString(), page, region, Math.max(1, Math.min(50, remaining)));
        return new String(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    static BufferedImage localPage(byte[] bytes, String mime, int page) throws IOException {
        if (page < 1 || page > MAX_PAGES) throw bad("Invalid page number");
        if ("application/pdf".equals(mime)) {
            try (var pdf = Loader.loadPDF(bytes)) {
                if (page > pdf.getNumberOfPages()) throw bad("Page is outside this document");
                validatePage(pdf.getPage(page - 1));
                var renderer = new PDFRenderer(pdf);
                renderer.setSubsamplingAllowed(true);
                return renderer.renderImageWithDPI(page - 1, 144);
            }
        }
        if (page != 1) throw bad("Images have only one page");
        return image(bytes);
    }

    private static void validatePage(org.apache.pdfbox.pdmodel.PDPage page) {
        var box = page.getCropBox();
        double width = box.getWidth(), height = box.getHeight();
        if (!Double.isFinite(width * height) || width < 1 || height < 1 || width * height * 4 > MAX_PIXELS
            || width > 8_000 || height > 8_000) throw bad("PDF page dimensions are too large");
    }

    private static BufferedImage image(byte[] bytes) throws IOException {
        try (var in = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw bad("Image cannot be decoded");
            var reader = readers.next();
            try {
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS) throw bad("Image exceeds 16 megapixels");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
    public static BufferedImage crop(BufferedImage image,Map<String,Object> region) {
        double x=number(region,"x"), y=number(region,"y"), w=number(region,"width"), h=number(region,"height");
        if(!Double.isFinite(x+y+w+h)||x<0||y<0||w<=0||h<=0||x+w>1.000001||y+h>1.000001) throw bad("Region must fit inside the page");
        int left=(int)(x*image.getWidth()),top=(int)(y*image.getHeight());
        int width=Math.min(image.getWidth()-left,Math.max(1,(int)(w*image.getWidth())));
        int height=Math.min(image.getHeight()-top,Math.max(1,(int)(h*image.getHeight())));
        if(width<1||height<1) throw bad("Region is too small");
        return image.getSubimage(left,top,width,height);
    }
    private static double number(Map<String,Object> m,String k) { if(!(m.get(k) instanceof Number n)) throw bad("Missing region coordinate"); return n.doubleValue(); }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,message);}
}
