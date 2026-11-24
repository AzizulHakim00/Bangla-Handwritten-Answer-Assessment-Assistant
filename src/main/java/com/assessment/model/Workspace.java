package com.assessment.model;

import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/** Single-school MVP aggregate. Versioned saves make review/approval atomic. */
@Document("workspaces")
public class Workspace {
    @Id public String id = "school";
    @Version public Long version;
    public List<Map<String, Object>> exams = new ArrayList<>();
    public List<Map<String, Object>> scripts = new ArrayList<>();
    public List<Map<String, Object>> criteria = new ArrayList<>();
}
