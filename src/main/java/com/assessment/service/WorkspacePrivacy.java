package com.assessment.service;

import com.assessment.model.Workspace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import org.bson.types.Binary;
import org.springframework.stereotype.Component;

@Component
public class WorkspacePrivacy {
    private static final List<String> PROTECTED = List.of("ocr", "remarks", "answers", "history", "approvedSnapshot",
        "approvedRevisions", "submittedRevisions", "appeals", "automationPages");
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final DocumentService documents;

    public WorkspacePrivacy(DocumentService documents) { this.documents = documents; }

    Workspace seal(Workspace workspace) {
        var stored = new Workspace();
        stored.id = workspace.id;
        stored.version = workspace.version;
        stored.exams = workspace.exams;
        stored.criteria = workspace.criteria;
        stored.rubrics = workspace.rubrics;
        stored.jobs = workspace.jobs;
        stored.audit = workspace.audit;
        for (var script : workspace.scripts) stored.scripts.add(sealScript(workspace, script));
        return stored;
    }

    Map<String, Object> sealScript(Workspace workspace, Map<String, Object> script) {
        var available = new LinkedHashMap<>(script);
        var privateFields = new LinkedHashMap<String, Object>();
        for (String field : PROTECTED) {
            if (available.containsKey(field)) privateFields.put(field, available.remove(field));
        }
        available.remove("filename");
        try {
            available.put("protected", new Binary(documents.seal(json.writeValueAsBytes(privateFields), context(workspace, script))));
        } catch (IOException ex) {
            throw new UncheckedIOException("Workspace encryption failed", ex);
        }
        return available;
    }

    Workspace unseal(Workspace workspace) {
        for (var script : workspace.scripts) {
            script.remove("filename");
            Object encrypted = script.remove("protected");
            if (encrypted == null) continue;
            try {
                byte[] bytes = encrypted instanceof Binary binary ? binary.getData() : (byte[]) encrypted;
                var fields = json.readValue(documents.unseal(bytes, context(workspace, script)),
                    new TypeReference<Map<String, Object>>() {});
                if (!PROTECTED.containsAll(fields.keySet())) throw new IOException("Unexpected protected field");
                script.putAll(fields);
            } catch (IOException ex) {
                throw new UncheckedIOException("Workspace data authentication failed", ex);
            }
        }
        return workspace;
    }

    private static String context(Workspace workspace, Map<String, Object> script) {
        return workspace.id + "|" + script.get("id") + "|1|assessment-record";
    }
}
