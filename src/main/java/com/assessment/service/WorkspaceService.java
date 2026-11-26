package com.assessment.service;

import com.assessment.model.Workspace;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class WorkspaceService {
    private final MongoTemplate mongo;
    private final WorkspacePrivacy privacy;

    private final WorkspaceRepository repository;

    public WorkspaceService(MongoTemplate mongo) { this(mongo, null, false); }

    @Autowired
    public WorkspaceService(MongoTemplate mongo, WorkspacePrivacy privacy,
        @Value("${assessment.partitioned:false}") boolean partitioned) {
        this.mongo = mongo;
        this.privacy = privacy;
        this.repository = partitioned ? new WorkspaceRepository(mongo, privacy) : null;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        if (repository != null) repository.ensureIndexes();
    }

    public Workspace read() {
        return read(AccessScope.tenant());
    }
    public Workspace readWithoutAudit() {
        return readWithoutAudit(AccessScope.tenant());
    }

    public Workspace readWithoutAudit(String tenant) {
        return repository == null ? read(tenant) : readPartitioned(tenant, false);
    }

    private Workspace readPartitioned(String tenant, boolean includeAudit) {
        for (int attempt = 0; ; attempt++) {
            try { return repository.read(tenant, includeAudit); }
            catch (RuntimeException ex) {
                if (attempt == 2 || !WorkspaceRepository.writeConflict(ex)) throw ex;
            }
        }
    }

    public Workspace read(String tenant) {
        if (repository != null) return readPartitioned(tenant, true);
        Workspace w = mongo.findById(tenant, Workspace.class);
        if (w != null) {
            if (w.partitioned) throw new IllegalStateException("Enable replica-set workspace storage for this database");
            return privacy == null ? w : privacy.unseal(w);
        }
        try { var created = new Workspace(); created.id = tenant; return mongo.insert(created); }
        catch (DuplicateKeyException ex) {
            var found = mongo.findById(tenant, Workspace.class);
            return privacy == null ? found : privacy.unseal(found);
        }
    }
    public Workspace change(long expected, Consumer<Workspace> mutation) {
        return change(AccessScope.tenant(), expected, mutation);
    }
    public Workspace change(String tenant, long expected, Consumer<Workspace> mutation) {
        if (repository != null) {
            try { return repository.change(tenant, expected, mutation); }
            catch (RuntimeException ex) {
                if (ex instanceof OptimisticLockingFailureException || WorkspaceRepository.writeConflict(ex)) throw conflict();
                throw ex;
            }
        }
        Workspace w = read(tenant);
        if (w.version == null || w.version != expected) throw conflict();
        mutation.accept(w);
        try {
            if (privacy == null) return mongo.save(w);
            var saved = mongo.save(privacy.seal(w));
            w.version = saved.version;
            return w;
        }
        catch (OptimisticLockingFailureException ex) { throw conflict(); }
    }
    public List<String> tenants() {
        return mongo.query(Workspace.class).distinct("_id").as(String.class).all();
    }
    static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Workspace changed. Reload before saving.");
    }
    public static Map<String,Object> script(Workspace w, String id) {
        return w.scripts.stream().filter(s -> id.equals(s.get("id")) && AccessScope.visible(s)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Script not found"));
    }
}
