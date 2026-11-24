package com.assessment.service;

import com.assessment.model.Workspace;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ReportService {
    private final DocumentService documents;
    public ReportService(DocumentService documents){this.documents=documents;}
    public byte[] pdf(Workspace w) throws IOException {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            List<String> lines=new ArrayList<>();lines.add("APPROVED MARK SHEET");lines.add("School assessment | Workspace version: "+w.version);
            lines.add("Generated: "+Instant.now());lines.add("Last approved scores are retained while appeals are reviewed.");lines.add("");
            for(var s:w.scripts){Map<?,?> approved=approved(s);if(approved==null)continue;
                lines.add(s.get("id").toString());lines.add("Exam: "+s.get("exam"));
                lines.add("Score: "+approved.get("score")+" / "+approved.get("maximum")+" | Approved revision");lines.add("");}
            if(lines.size()==5)lines.add("No approved marks available.");
            var font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for(int start=0;start<lines.size();start+=48){var page=new PDPage(PDRectangle.A4);document.addPage(page);
                try(var stream=new PDPageContentStream(document,page)){stream.beginText();stream.setFont(font,10);stream.setLeading(15);stream.newLineAtOffset(40,790);
                    for(int i=start;i<Math.min(start+48,lines.size());i++){stream.showText(lines.get(i));stream.newLine();}stream.endText();}}
            document.save(output);return output.toByteArray();
        }
    }
    public synchronized byte[] signedBundle(Workspace w) throws Exception {
        Path keyFile=documents.storage.resolve(".report-signing-keypair.json");
        var factory=KeyFactory.getInstance("Ed25519");var mapper=new ObjectMapper();
        if(!Files.exists(keyFile)){
            var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path temporary=Files.createTempFile(documents.storage,".signing-",".tmp");
            try {
                try{Files.setPosixFilePermissions(temporary,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
                mapper.writeValue(temporary.toFile(),Map.of("private",Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                    "public",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));
                Files.move(temporary,keyFile,StandardCopyOption.ATOMIC_MOVE);
            } finally {Files.deleteIfExists(temporary);}
        }
        var keys=mapper.readTree(keyFile.toFile());
        PrivateKey privateKey=factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keys.get("private").asText())));
        PublicKey publicKey=factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(keys.get("public").asText())));
        byte[] pdf=pdf(w);List<Map<String,Object>> results=new ArrayList<>();
        for(var s:w.scripts){var approved=approved(s);if(approved==null)continue;
            results.add(Map.of("scriptId",s.get("id"),"examId",s.get("exam"),"approved",approved,
                "documentSha256",hex(Files.readAllBytes(documents.path(s.get("id").toString())))));}
        byte[] manifest=new ObjectMapper().writeValueAsBytes(Map.of("format","assessment-report-v1","workspaceVersion",w.version,
            "generatedAt",Instant.now().toString(),"pdfSha256",hex(pdf),"results",results));
        var signature=Signature.getInstance("Ed25519");signature.initSign(privateKey);signature.update(manifest);
        String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(publicKey.getEncoded())+"\n-----END PUBLIC KEY-----\n";
        try(var out=new ByteArrayOutputStream();var zip=new ZipOutputStream(out)){
            entry(zip,"approved-marks.pdf",pdf);entry(zip,"manifest.json",manifest);entry(zip,"manifest.sig",signature.sign());
            entry(zip,"public-key.pem",pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII));zip.finish();return out.toByteArray();}
    }
    public static Map<?,?> approved(Map<String,Object> s){return s.get("approvedSnapshot") instanceof Map<?,?> m?m:"Approved".equals(s.get("status"))?s:null;}
    public static String hex(byte[] data) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
}
