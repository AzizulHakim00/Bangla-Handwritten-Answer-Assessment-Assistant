package com.assessment.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

final class LoginThrottle extends OncePerRequestFilter {
    private record Window(long until, int attempts) {}
    private final Map<String, Window> sources = new LinkedHashMap<>();
    private final Clock clock = Clock.systemUTC();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/login".equals(request.getServletPath().isEmpty()
            ? request.getRequestURI() : request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (limited(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "60");
            response.sendError(429, "Too many sign-in attempts. Try again in one minute.");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean limited(String key) {
        long now = clock.millis();
        sources.values().removeIf(window -> window.until() <= now);
        var old = sources.get(key);
        if (old == null && sources.size() >= 10_000) return true;
        if (old != null && old.attempts() >= 15) return true;
        sources.put(key, old == null ? new Window(now + 60_000, 1) : new Window(old.until(), old.attempts() + 1));
        return false;
    }
}
