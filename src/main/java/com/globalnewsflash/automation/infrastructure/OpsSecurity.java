package com.globalnewsflash.automation.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OpsSecurity {
    @Bean
    SecurityFilterChain opsSecurityFilterChain(HttpSecurity http,
            @Value("${news.ops.token:}") String token) throws Exception {
        if (!token.isEmpty() && token.length() < 32) {
            throw new IllegalStateException("NEWS_OPS_TOKEN must have at least 32 characters, or be absent to disable access");
        }
        return http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll()
                        .anyRequest().hasRole("OPS"))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401))
                        .accessDeniedHandler((req, res, ex) -> res.sendError(403)))
                .addFilterBefore(new TokenFilter(token), AnonymousAuthenticationFilter.class)
                .build();
    }

    private static final class TokenFilter extends OncePerRequestFilter {
        private final byte[] expected;

        TokenFilter(String token) { expected = token.getBytes(StandardCharsets.UTF_8); }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            String supplied = request.getHeader("X-Ops-Token");
            if (expected.length >= 32 && supplied != null && supplied.length() <= 1024
                    && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                var authentication = new UsernamePasswordAuthenticationToken("operator", null,
                        List.of(new SimpleGrantedAuthority("ROLE_OPS")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            chain.doFilter(request, response);
        }
    }
}
