package com.assessment.service;

import com.assessment.model.Workspace;
import com.mongodb.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.transaction.support.TransactionTemplate;

final class WorkspaceRepository {
    private final MongoTemplate mongo;
    private final WorkspacePrivacy privacy;
    private final TransactionTemplate transactions;

    WorkspaceRepository(MongoTemplate mongo, WorkspacePrivacy privacy) {
        this.mongo = mongo;
        this.privacy = privacy;
        var options = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY).build();
        this.transactions = new TransactionTemplate(new MongoTransactionManager(mongo.getMongoDatabaseFactory(), options));
    }

    Workspace read(String tenant, boolean includeAudit) {
        return transactions.execute(status -> {
            var workspace = load(tenant, includeAudit);
            if (workspace != null) return workspace;
            var initial = new Workspace();
            initial.id = tenant;
            initial.partitioned = true;
            return mongo.insert(initial);
        });
    }

    Workspace change(String tenant, long expected, Consumer<Workspace> action) {
        return transactions.execute(status -> {
            var workspace = load(tenant, true);
            if (workspace == null || workspace.version == null || workspace.version != expected) throw WorkspaceService.conflict();
            boolean migrate = !workspace.partitioned;
            var before = migrate ? Map.<String, Map<String, Map<String, Object>>>of() : snapshot(workspace);
            action.accept(workspace);
            var header = new Workspace();
            header.id = tenant;
            header.version = workspace.version;
            header.partitioned = true;
            header.criteria = workspace.criteria;
            workspace.version = mongo.save(header).version;
            workspace.partitioned = true;
            for (var group : groups(workspace).entrySet()) {
                var old = before.getOrDefault(group.getKey(), Map.of());
                for (int i = 0; i < group.getValue().size(); i++) {
                    var item = group.getValue().get(i);
                    String id = item.get("id").toString();
                    if (item.equals(old.get(id))) continue;
                    var stored = "scripts".equals(group.getKey()) ? privacy.sealScript(workspace, item) : item;
                    var record = new Document("_id", tenant + "|" + id).append("tenant", tenant)
                        .append("position", i).append("record", bson(stored));
                    mongo.save(record, group.getKey());
                }
            }
            return workspace;
        });
    }

    private Workspace load(String tenant, boolean includeAudit) {
        var workspace = mongo.findById(tenant, Workspace.class);
        if (workspace == null) return null;
        if (workspace.partitioned) {
            workspace.exams = find(tenant, "exams");
            workspace.scripts = find(tenant, "scripts");
            workspace.rubrics = find(tenant, "rubric_versions");
            workspace.jobs = find(tenant, "assessment_jobs");
            if (includeAudit) workspace.audit = find(tenant, "audit_events");
        }
        workspace.criteria = normalized(workspace.criteria);
        workspace.exams = normalized(workspace.exams);
        return privacy.unseal(workspace);
    }

    private List<Map<String, Object>> find(String tenant, String collection) {
        var query = Query.query(Criteria.where("tenant").is(tenant)).with(Sort.by("position"));
        return new ArrayList<>(mongo.find(query, Document.class, collection).stream().map(row ->
            WorkspaceRepository.<Map<String, Object>>normalized(row.get("record", Document.class))).toList());
    }

    void ensureIndexes() {
        for (String collection : List.of("exams", "scripts", "rubric_versions", "assessment_jobs", "audit_events")) {
            mongo.indexOps(collection).ensureIndex(new Index().on("tenant", Sort.Direction.ASC).on("position", Sort.Direction.ASC));
        }
        mongo.indexOps("scripts").ensureIndex(new Index().on("tenant", Sort.Direction.ASC).on("record.exam", Sort.Direction.ASC)
            .on("record.candidateToken", Sort.Direction.ASC));
    }

    static boolean writeConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DuplicateKeyException) return true;
            if (cause instanceof MongoException mongoError && (mongoError.getCode() == 112
                || mongoError.hasErrorLabel("TransientTransactionError"))) return true;
        }
        return false;
    }

    private static Map<String, List<Map<String, Object>>> groups(Workspace workspace) {
        return Map.of("exams", workspace.exams, "scripts", workspace.scripts, "rubric_versions", workspace.rubrics,
            "assessment_jobs", workspace.jobs, "audit_events", workspace.audit);
    }

    private static Map<String, Map<String, Map<String, Object>>> snapshot(Workspace workspace) {
        var snapshot = new LinkedHashMap<String, Map<String, Map<String, Object>>>();
        groups(workspace).forEach((collection, items) -> {
            var copies = new LinkedHashMap<String, Map<String, Object>>();
            items.forEach(item -> copies.put(item.get("id").toString(), normalized(item)));
            snapshot.put(collection, copies);
        });
        return snapshot;
    }

    private static Object bson(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new Document();
            map.forEach((key, data) -> result.put(key.toString(), bson(data)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(WorkspaceRepository::bson).toList();
        return value instanceof BigDecimal decimal ? new Decimal128(decimal) : value;
    }

    @SuppressWarnings("unchecked")
    private static <T> T normalized(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<>();
            map.forEach((key, data) -> result.put(key, normalized(data)));
            return (T) result;
        }
        if (value instanceof List<?> list) {
            var result = new ArrayList<>();
            list.forEach(item -> result.add(normalized(item)));
            return (T) result;
        }
        return (T) (value instanceof Decimal128 number ? number.bigDecimalValue() : value);
    }
}
