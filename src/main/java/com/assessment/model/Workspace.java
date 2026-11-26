package com.assessment.model;

import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Tenant aggregate. Versioned saves make submission, moderation and appeal transitions atomic. */
@Document("workspaces")
public class Workspace {
    @Id public String id = "school";
    @Version public Long version;
    public boolean partitioned;
    public List<Map<String, Object>> exams = new ArrayList<>();
    public List<Map<String, Object>> scripts = new ArrayList<>();
    public List<Map<String, Object>> rubrics = new ArrayList<>();
    public List<Map<String, Object>> jobs = new ArrayList<>();
    public List<Map<String, Object>> audit = new ArrayList<>();
    public List<Map<String, Object>> criteria = new ArrayList<>();
}
