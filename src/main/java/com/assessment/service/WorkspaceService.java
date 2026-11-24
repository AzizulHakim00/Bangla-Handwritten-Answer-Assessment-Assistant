package com.assessment.service;

import com.assessment.model.Workspace;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class WorkspaceService {
    private final MongoTemplate mongo;
    public WorkspaceService(MongoTemplate mongo) { this.mongo = mongo; }
    public Workspace read() {
        Workspace w = mongo.findById("school", Workspace.class);
        if (w != null) return w;
        try { return mongo.insert(new Workspace()); }
        catch (DuplicateKeyException ex) { return mongo.findById("school", Workspace.class); }
    }
    public Workspace change(long expected, Consumer<Workspace> mutation) {
        Workspace w = read();
        if (w.version == null || w.version != expected) throw conflict();
        mutation.accept(w);
        try { return mongo.save(w); }
        catch (OptimisticLockingFailureException ex) { throw conflict(); }
    }
    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Workspace changed. Reload before saving.");
    }
    public static Map<String,Object> script(Workspace w, String id) {
        return w.scripts.stream().filter(s -> id.equals(s.get("id"))).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Script not found"));
    }
}
