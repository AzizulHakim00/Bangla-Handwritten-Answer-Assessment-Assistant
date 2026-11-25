package com.assessment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    @Bean UserDetailsService users(Environment env) {
        var users = new InMemoryUserDetailsManager();
        var encoder = new BCryptPasswordEncoder();
        for (String role : new String[]{"TEACHER", "MARKER", "MODERATOR"}) {
            String password = env.getProperty(role + "_PASSWORD");
            if (password == null || password.length() < 12)
                throw new IllegalStateException("Set " + role + "_PASSWORD to at least 12 characters");
            users.createUser(User.withUsername(role.toLowerCase() + "@school.local")
                .password(encoder.encode(password)).roles(role).build());
        }
        return users;
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a
            .requestMatchers("/login", "/css/**", "/js/**", "/error").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/exams", "/api/rubrics", "/api/uploads").hasRole("TEACHER")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/scripts/*/approve", "/api/scripts/*/return").hasRole("MODERATOR")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/scripts/*/appeals").hasRole("TEACHER")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/scripts/*/review", "/api/scripts/*/region", "/api/scripts/*/ocr").hasAnyRole("TEACHER", "MARKER")
            .anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/dashboard", true).permitAll())
            .logout(l -> l.logoutSuccessUrl("/login?logout"))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                (req, res, ex) -> res.sendError(401), new AntPathRequestMatcher("/api/**")))
            .headers(h -> h.frameOptions(f -> f.sameOrigin()).contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' blob:; frame-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'")))
            .build();
    }
}
