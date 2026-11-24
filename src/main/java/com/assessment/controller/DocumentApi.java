package com.assessment.controller;

import com.assessment.service.*;
import com.assessment.model.Workspace;
import java.util.*;
import java.io.*;
import java.time.Instant;
import java.security.Principal;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DocumentApi {
    private final WorkspaceService workspaces;private final DocumentService documents;private final ReportService reports;
    public DocumentApi(WorkspaceService w,DocumentService d,ReportService r){workspaces=w;documents=d;reports=r;}
    @GetMapping("/scripts/{id}/pages/{page}") public ResponseEntity<byte[]> page(@PathVariable String id,@PathVariable int page)throws Exception {
        var image=documents.page(WorkspaceService.script(workspaces.read(),id),page);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(out.toByteArray());
    }
    public record RegionRequest(long version,int question,int page,double x,double y,double width,double height){}
    @PostMapping("/scripts/{id}/region") public Workspace region(@PathVariable String id,@RequestBody RegionRequest r,Principal user)throws Exception {
        var selected=WorkspaceService.script(workspaces.read(),id);
        Map<String,Object> region=new LinkedHashMap<>(Map.of("page",r.page,"x",r.x,"y",r.y,"width",r.width,"height",r.height));
        DocumentService.crop(documents.page(selected,r.page),region);
        return workspaces.change(r.version,w->{var s=WorkspaceService.script(w,id);editable(s);var a=answer(s,r.question);
            a.put("region",region);a.put("page",r.page);a.put("reviewed",false);a.remove("ocrJob");
            audit(s,"Question "+r.question+" region saved",user.getName());});
    }
    public record OcrRequest(long version,int question){}
    @PostMapping("/scripts/{id}/ocr") public Workspace ocr(@PathVariable String id,@RequestBody OcrRequest r,Principal user) {
        return workspaces.change(r.version,w->{var s=WorkspaceService.script(w,id);editable(s);var a=answer(s,r.question);
            if(!(a.get("region") instanceof Map<?,?> region))throw bad("Save the answer region before starting OCR");
            a.put("ocrJob",new LinkedHashMap<>(Map.of("id",UUID.randomUUID().toString(),"status","PENDING",
                "region",new LinkedHashMap<>(region),"requestedBy",user.getName(),"requestedAt",Instant.now().toString())));
            audit(s,"Question "+r.question+" local OCR requested",user.getName());});
    }
    @GetMapping("/exports/marks.pdf") public ResponseEntity<byte[]> pdf()throws Exception{return download(reports.pdf(workspaces.read()),"application/pdf","approved-marks.pdf");}
    @GetMapping("/exports/marks.zip") public ResponseEntity<byte[]> bundle()throws Exception{return download(reports.signedBundle(workspaces.read()),"application/zip","approved-marks-signed.zip");}
    private static ResponseEntity<byte[]> download(byte[] bytes,String mime,String name){return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(mime)).header("Content-Disposition","attachment; filename="+name).body(bytes);}
    private static Map<String,Object> answer(Map<String,Object> s,int q){var answers=AssessmentApi.answers(s);if(q<1||q>answers.size())throw bad("Question outside this script");return answers.get(q-1);}
    private static void editable(Map<String,Object> s){if(List.of("Approved","Pending Moderation").contains(s.get("status")))throw bad("Submitted or approved marks cannot be changed");}
    @SuppressWarnings("unchecked") private static void audit(Map<String,Object> s,String action,String user){String time=Instant.now().toString();s.put("updated",time);
        ((List<Map<String,Object>>)s.get("history")).add(new LinkedHashMap<>(Map.of("action",action,"time",time,"user",user,"score",s.get("score"))));}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
