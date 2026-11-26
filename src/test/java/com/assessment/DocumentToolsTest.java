package com.assessment;

import com.assessment.model.Workspace;
import com.assessment.service.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DocumentToolsTest {
    @TempDir Path temp;
    @Test void regionMustFitInsideTheOriginal() {
        var image=new BufferedImage(100,200,BufferedImage.TYPE_INT_RGB);
        var crop=DocumentService.crop(image,Map.of("x",.1,"y",.2,"width",.5,"height",.5));
        assertEquals(50,crop.getWidth());assertEquals(100,crop.getHeight());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->DocumentService.crop(image,Map.of("x",.8,"y",0,"width",.5,"height",1)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->DocumentService.crop(image,Map.of("x",0,"y",0,"width",Double.NaN,"height",1)));
    }
    @Test void nativeImagePageBoundsAreEnforced() throws Exception {
        var documents=new DocumentService(temp.toString());
        ImageIO.write(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB),"png",temp.resolve("SCR-test").toFile());
        var script=new LinkedHashMap<String,Object>(Map.of("id","SCR-test","mime","image/png"));
        assertEquals(100,documents.page(script,1).getWidth());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->documents.page(script,2));
    }
    @Test void pdfUsesApprovedSnapshotDuringAppeal() throws Exception {
        var w=workspace();var report=new ReportService(new DocumentService(temp.toString()));
        try(var pdf=Loader.loadPDF(report.pdf(w))){var text=new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Score: 6 / 7"));assertFalse(text.contains("Score: 2 / 7"));}
    }
    @Test void detachedSignatureDetectsTamperingAndPersistsIdentity() throws Exception {
        Files.writeString(temp.resolve("SCR-test"),"original document");
        var report=new ReportService(new DocumentService(temp.toString()));
        var first=bundle(report.signedBundle(workspace()));var second=bundle(report.signedBundle(workspace()));
        assertArrayEquals(first.get("public-key.pem"),second.get("public-key.pem"));
        String pem=new String(first.get("public-key.pem"),java.nio.charset.StandardCharsets.US_ASCII).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        var verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(first.get("manifest.json"));assertTrue(verifier.verify(first.get("manifest.sig")));
        byte[] changed=first.get("manifest.json").clone();changed[5]^=1;
        verifier.initVerify(key);verifier.update(changed);assertFalse(verifier.verify(first.get("manifest.sig")));
        var manifest=new com.fasterxml.jackson.databind.ObjectMapper().readTree(first.get("manifest.json"));
        assertEquals(ReportService.hex(first.get("approved-marks.pdf")),manifest.get("pdfSha256").asText());
    }
    @Test @SuppressWarnings("unchecked") void failedOcrNeverReplacesTranscriptionOrScore() throws Exception {
        var documents=new DocumentService(temp.toString());
        ImageIO.write(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB),"png",temp.resolve("SCR-test").toFile());
        var a=new LinkedHashMap<String,Object>(Map.of("question",1,"ocr","Verified manual text","score",5,"maximum",7));
        a.put("ocrJob",new LinkedHashMap<>(Map.of("id","job-1","status","PENDING","region",Map.of("page",1,"x",0,"y",0,"width",1,"height",1))));
        var s=new LinkedHashMap<String,Object>(Map.of("id","SCR-test","mime","image/png","answers",new ArrayList<>(java.util.List.of(a))));
        var w=new Workspace();w.version=1L;w.scripts.add(s);
        var service=mock(WorkspaceService.class);when(service.read()).thenReturn(w);
        when(service.change(anyLong(),any())).thenAnswer(i->{((Consumer<Workspace>)i.getArgument(1)).accept(w);return w;});
        new OcrWorker(service,documents,temp.resolve("missing-engine").toString()).tick();
        assertEquals("FAILED",((Map<?,?>)a.get("ocrJob")).get("status"));assertEquals("Verified manual text",a.get("ocr"));assertEquals(5,a.get("score"));
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="OCR_NATIVE_TEST", matches="true")
    void bengaliOcrRetainsBengaliTextAndAnEnglishTerm() throws Exception {
        var image = new BufferedImage(950, 230, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        String bengali = "বাংলা দ্বিতীয় পত্র";
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            var font = new Font("Noto Sans Bengali", Font.PLAIN, 48);
            assertEquals(-1, font.canDisplayUpTo(bengali), "Install Noto Sans Bengali for the OCR test");
            graphics.setFont(font);
            graphics.drawString(bengali, 55, 90);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 48));
            graphics.drawString("Photosynthesis", 55, 175);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", temp.resolve("SCR-bengali").toFile());
        var script = Map.<String, Object>of("id", "SCR-bengali", "mime", "image/png");
        var wholePage = Map.<String, Object>of("page", 1, "x", 0, "y", 0, "width", 1, "height", 1);
        var worker = new OcrWorker(mock(WorkspaceService.class), new DocumentService(temp.toString()), "tesseract");
        var result = worker.recognize(script, java.util.List.of(wholePage));
        var text = java.text.Normalizer.normalize(result.get("candidate").toString(), java.text.Normalizer.Form.NFC);
        assertTrue(text.contains(java.text.Normalizer.normalize(bengali, java.text.Normalizer.Form.NFC)), text);
        assertTrue(text.contains("Photosynthesis"), text);
        assertEquals("NEEDS_REVIEW", result.get("status"));
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="OCR_NATIVE_TEST", matches="true")
    @SuppressWarnings("unchecked") void installedEngineProducesOnlyAnUnverifiedCandidate() throws Exception {
        var documents=new DocumentService(temp.toString());var image=new BufferedImage(800,140,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,800,140);g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif",Font.BOLD,48));g.drawString("HELLO WORLD",40,90);g.dispose();
        ImageIO.write(image,"png",temp.resolve("SCR-native").toFile());
        var a=new LinkedHashMap<String,Object>(Map.of("question",1,"ocr","Human text","score",5));
        a.put("ocrJob",new LinkedHashMap<>(Map.of("id","native","status","PENDING","region",Map.of("page",1,"x",0,"y",0,"width",1,"height",1))));
        var w=new Workspace();w.version=1L;w.scripts.add(new LinkedHashMap<>(Map.of("id","SCR-native","mime","image/png","answers",new ArrayList<>(java.util.List.of(a)))));
        var service=mock(WorkspaceService.class);when(service.read()).thenReturn(w);
        when(service.change(anyLong(),any())).thenAnswer(i->{((Consumer<Workspace>)i.getArgument(1)).accept(w);return w;});
        new OcrWorker(service,documents,"tesseract").tick();
        var job=(Map<?,?>)a.get("ocrJob");assertEquals("NEEDS_REVIEW",job.get("status"));assertTrue(job.get("candidate").toString().contains("HELLO"));
        assertEquals("Human text",a.get("ocr"));assertEquals(5,a.get("score"));
    }
    private static Workspace workspace(){var w=new Workspace();w.version=3L;
        w.scripts.add(new LinkedHashMap<>(Map.of("id","SCR-test","exam","EX-test","status","Appeal Review","score",2,"maximum",7,
            "approvedSnapshot",Map.of("score",6,"maximum",7))));return w;}
    private static Map<String,byte[]> bundle(byte[] zip)throws Exception{Map<String,byte[]> entries=new HashMap<>();try(var in=new ZipInputStream(new ByteArrayInputStream(zip))){java.util.zip.ZipEntry e;while((e=in.getNextEntry())!=null)entries.put(e.getName(),in.readAllBytes());}return entries;}
}
