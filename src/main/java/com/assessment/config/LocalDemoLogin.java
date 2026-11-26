package com.assessment.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.springframework.http.HttpMethod.POST;

public final class LocalDemoLogin {
    private static final List<String> PROXY_HEADERS = List.of(
        "Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Assessment-Proxy");
    private final Environment env;
    private final boolean enabled;

    LocalDemoLogin(Environment env, PilotMfa mfa) {
        this.env = env;
        enabled = env.getProperty("ASSESSMENT_LOCAL_DEMO", Boolean.class, false)
            && env.getProperty("ASSESSMENT_ACCOUNTS_JSON", "").isBlank()
            && !env.getProperty("ASSESSMENT_PILOT", Boolean.class, false)
            && !env.getProperty("ASSESSMENT_SECURE_COOKIES", Boolean.class, false)
            && "none".equalsIgnoreCase(env.getProperty("ASSESSMENT_FORWARD_HEADERS", "none"))
            && !mfa.hasConfiguredAccounts();
    }

    public boolean available(HttpServletRequest request) {
        String host = request.getServerName();
        return enabled && !request.isSecure()
            && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))
            && PROXY_HEADERS.stream().noneMatch(header -> request.getHeader(header) != null);
    }

    OncePerRequestFilter roleLoginFilter() {
        var login = PathPatternRequestMatcher.withDefaults().matcher(POST, "/login");
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !login.matches(request) || request.getParameter("demoRole") == null;
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
                if (!available(request)) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                String role = request.getParameter("demoRole");
                if (!InstitutionAccounts.ROLES.contains(role)) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
                // Keep the role password on the server and use the standard sign-in filter and session handling.
                String username = InstitutionAccounts.defaultEmail(role, "school");
                String password = env.getRequiredProperty(role + "_PASSWORD");
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override
                    public String getParameter(String name) {
                        return switch (name) {
                            case "username" -> username;
                            case "password" -> password;
                            default -> super.getParameter(name);
                        };
                    }
                }, response);
            }
        };
    }
}
