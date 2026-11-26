package com.assessment.service;

import com.assessment.model.Workspace;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public final class AccessScope {
    private AccessScope() {}

    private static String authority(String prefix, String fallback) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return fallback;
        return auth.getAuthorities().stream().map(a -> a.getAuthority())
            .filter(a -> a.startsWith(prefix)).map(a -> a.substring(prefix.length()))
            .findFirst().orElse(fallback);
    }

    public static String tenant() { return authority("TENANT_", "school"); }
    public static String role() { return authority("ROLE_", ""); }
    public static String user() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

    public static boolean visible(Map<String, Object> script) {
        String role = role();
        if ("MARKER".equals(role)) return assigned(script, "marker");
        if ("MODERATOR".equals(role)) return assigned(script, "moderator");
        return true;
    }

    private static boolean assigned(Map<String, Object> script, String field) {
        // Records created before assignments were introduced retain the original school workflow.
        return script.containsKey(field) ? user().equals(script.get(field)) :
            "school".equals(tenant()) && !script.containsKey("tenant");
    }

    public static Workspace view(Workspace source) {
        var view = new Workspace();
        view.id = source.id;
        view.version = source.version;
        view.scripts = source.scripts.stream().filter(AccessScope::visible).toList();
        var visibleIds = new java.util.HashSet<>(view.scripts.stream().map(s -> s.get("id")).toList());
        view.jobs = source.jobs.stream().filter(job -> visibleIds.contains(job.get("scriptId"))).toList();
        if ("MARKER".equals(role()) || "MODERATOR".equals(role())) {
            var exams = view.scripts.stream().map(s -> s.get("exam")).toList();
            view.exams = source.exams.stream().filter(e -> exams.contains(e.get("id"))).toList();
            view.criteria = List.of();
        } else {
            view.exams = source.exams;
            view.rubrics = source.rubrics;
            view.criteria = source.criteria;
        }
        return view;
    }

    public static void requireVisible(Map<String, Object> script) {
        if (!visible(script)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Script not found");
    }
}
