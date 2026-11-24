package com.assessment.service;

import com.assessment.controller.AssessmentApi;
import java.util.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Durable answer-local job, one worker, bounded native process, no automatic mark changes. */
@Service
public class OcrWorker {
    private final WorkspaceService workspaces; private final DocumentService documents; private final String command;
    public OcrWorker(WorkspaceService w,DocumentService d,@Value("${assessment.ocr-command:tesseract}") String c){workspaces=w;documents=d;command=c;}
    @Scheduled(fixedDelay=5000,initialDelay=10000)
    @SuppressWarnings("unchecked") public void tick() {
        try {
            var w=workspaces.read();
            for(var s:w.scripts) for(var a:AssessmentApi.answers(s)) {
                if(!(a.get("ocrJob") instanceof Map<?,?> raw)) continue;
                var job=(Map<String,Object>)raw;
                boolean expired="RUNNING".equals(job.get("status"))&&Instant.parse(job.get("startedAt").toString()).isBefore(Instant.now().minusSeconds(120));
                if(!"PENDING".equals(job.get("status"))&&!expired) continue;
                String id=s.get("id").toString(),jobId=job.get("id").toString(); int q=((Number)a.get("question")).intValue();
                workspaces.change(w.version,x->{var j=job(x,id,q,jobId);j.put("status","RUNNING");j.put("startedAt",Instant.now().toString());});
                run(s,job,id,q,jobId);return;
            }
        } catch(Exception ignored) { /* Database unavailable or optimistic conflict: next tick retries. */ }
    }
    private void run(Map<String,Object> s,Map<String,Object> job,String id,int q,String jobId) {
        Path temp=null;String text="",error="";boolean ok=false;
        try {
            temp=Files.createTempDirectory("answer-ocr-");
            @SuppressWarnings("unchecked") var region=(Map<String,Object>)job.get("region");
            var image=DocumentService.crop(documents.page(s,((Number)region.get("page")).intValue()),region);
            Path input=temp.resolve("input.png"),output=temp.resolve("result");ImageIO.write(image,"png",input.toFile());
            Process process=new ProcessBuilder(command,input.toString(),output.toString(),"-l","ben+eng","--psm","6")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(45,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);throw new Exception("timeout");}
            if(process.exitValue()!=0)throw new Exception("OCR engine error");
            Path result=Path.of(output+".txt");if(Files.size(result)>120000)throw new Exception("output too large");
            text=Files.readString(result).strip();if(text.length()>30000)throw new Exception("output too large");
            ok=true;
        } catch(Exception ex) { error="OCR could not read this region. Check the local engine and use manual transcription."; }
        finally { if(temp!=null)try(var files=Files.list(temp)){for(Path f:files.toList())Files.deleteIfExists(f);Files.deleteIfExists(temp);}catch(Exception ignored){} }
        final String candidate=text,message=error; final boolean success=ok;
        for(int attempt=0;attempt<3;attempt++)try {
            var current=workspaces.read();workspaces.change(current.version,w->{var j=job(w,id,q,jobId);
                j.put("status",success?"NEEDS_REVIEW":"FAILED");j.put("candidate",candidate);j.put("message",message);
                j.put("completedAt",Instant.now().toString());});return;
        } catch(Exception ignored) { /* A canceled/replaced job cannot overwrite a newer region or review. */ }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> job(com.assessment.model.Workspace w,String id,int q,String jobId) {
        var s=WorkspaceService.script(w,id);var a=AssessmentApi.answers(s).get(q-1);
        var j=(Map<String,Object>)a.get("ocrJob");if(j==null||!jobId.equals(j.get("id")))throw new IllegalStateException("Job replaced");return j;
    }
}
