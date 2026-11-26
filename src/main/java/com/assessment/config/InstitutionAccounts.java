package com.assessment.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import org.springframework.core.env.Environment;

public class InstitutionAccounts {
    static final List<String> ROLES = List.of("TEACHER", "MARKER", "MODERATOR");

    public record Member(String email, String tenant, String role, boolean active, String passwordHash) {}

    private final List<Member> members;

    public InstitutionAccounts(Environment env) throws IOException {
        String configured = env.getProperty("ASSESSMENT_ACCOUNTS_JSON", "");
        if (!configured.isBlank()) {
            members = List.copyOf(new ObjectMapper().readValue(configured, new TypeReference<List<Member>>() {}));
        } else {
            var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
            var local = new ArrayList<Member>();
            var passwords = new HashSet<String>();
            for (String role : ROLES) {
                String password = env.getProperty(role + "_PASSWORD");
                if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                    throw new IllegalStateException("Set " + role + "_PASSWORD to at least 12 characters");
                if (!passwords.add(password)) throw new IllegalStateException("Use distinct role passwords");
                local.add(new Member(defaultEmail(role, "school"), "school", role, true, encoder.encode(password)));
            }
            members = List.copyOf(local);
        }
        var emails = new HashSet<String>();
        for (var member : members) {
            if (member.email() == null || !member.email().matches("[a-z0-9._%+\\-]+@[a-z0-9.\\-]+")
                || member.tenant() == null || !member.tenant().matches("[a-z0-9][a-z0-9-]{0,62}")
                || !ROLES.contains(member.role())
                || member.passwordHash() == null || !member.passwordHash().matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")
                || !emails.add(member.email())) {
                throw new IllegalStateException("Invalid or duplicate institutional account");
            }
        }
    }

    public List<Member> members() { return members; }

    public static String defaultEmail(String role, String tenant) {
        return role.toLowerCase(Locale.ROOT) + "@" + tenant + ".local";
    }
}
