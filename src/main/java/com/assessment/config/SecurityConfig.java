package com.assessment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetails;

@Configuration
public class SecurityConfig {
    @Bean InstitutionAccounts institutionalAccounts(Environment env) throws java.io.IOException {
        return new InstitutionAccounts(env);
    }

    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService users(InstitutionAccounts accounts) {
        var users = new InMemoryUserDetailsManager();
        for (var account : accounts.members()) {
            users.createUser(User.withUsername(account.email()).password(account.passwordHash())
                .authorities("ROLE_" + account.role(), "TENANT_" + account.tenant())
                .disabled(!account.active()).build());
        }
        return users;
    }
    @Bean PilotMfa pilotMfa(Environment env, InstitutionAccounts accounts) throws java.io.IOException {
        return new PilotMfa(env, accounts);
    }

    @Bean DaoAuthenticationProvider institutionalLogin(UserDetailsService users, BCryptPasswordEncoder encoder, PilotMfa mfa) {
        var provider = new DaoAuthenticationProvider(users) {
            @Override
            protected void additionalAuthenticationChecks(UserDetails user, UsernamePasswordAuthenticationToken auth) {
                try {
                    super.additionalAuthenticationChecks(user, auth);
                    mfa.check(user.getUsername(), auth.getDetails());
                } finally {
                    if (auth.getDetails() instanceof PilotMfa.Code code) code.eraseCredentials();
                }
            }
        };
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean SecurityFilterChain security(HttpSecurity http, DaoAuthenticationProvider institutionalLogin) throws Exception {
        return http.authenticationProvider(institutionalLogin).addFilterBefore(new LoginThrottle(), UsernamePasswordAuthenticationFilter.class).authorizeHttpRequests(a -> a
            .requestMatchers("/login", "/css/**", "/js/**", "/error").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/exams", "/api/exams/*", "/api/rubrics", "/api/uploads", "/api/scripts/*/assignment", "/api/scripts/*/binding").hasRole("TEACHER")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/scripts/*/approve", "/api/scripts/*/return", "/api/scripts/*/appeals/resolve").hasRole("MODERATOR")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/scripts/*/appeals").hasAnyRole("TEACHER", "MODERATOR")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/scripts/*/review", "/api/scripts/*/ocr",
                "/api/v1/p33/script-assessments", "/api/v1/p33/mark-reviews", "/api/scripts/*/suggestion").hasRole("MARKER")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/scripts/*/region").hasAnyRole("TEACHER", "MARKER")
            .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/reviews").hasRole("MODERATOR")
            .requestMatchers("/api/exports/**", "/reports").hasAnyRole("TEACHER", "MODERATOR")
            .requestMatchers("/moderation").hasRole("MODERATOR")
            .requestMatchers("/exams", "/upload", "/rubrics").hasRole("TEACHER")
            .anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/dashboard", true)
                .authenticationDetailsSource(request -> new PilotMfa.Code(request.getParameter("otp"))).permitAll())
            .logout(l -> l.logoutSuccessUrl("/login?logout"))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(
                (req, res, ex) -> res.sendError(401), PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
            .headers(h -> h.frameOptions(f -> f.sameOrigin()).contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' blob:; frame-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'")))
            .build();
    }
}
