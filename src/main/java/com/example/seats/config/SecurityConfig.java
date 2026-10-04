package com.example.seats.config;

import com.example.seats.api.Models;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.secret}") String secret,
                          @Value("${app.jwt.issuer}") String issuer,
                          @Value("${app.jwt.audience}") String audience) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
        }
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            String subject = jwt.getSubject();
            boolean valid = jwt.getAudience().contains(audience) && jwt.getExpiresAt() != null
                    && subject != null && !subject.isBlank() && subject.length() <= 200;
            return valid ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), claims));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, JsonMapper mapper) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/metrics").permitAll()
                        .requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasAuthority("SCOPE_admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((req, res, e) -> writeError(mapper, res, 401, "UNAUTHENTICATED")))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, e) -> writeError(mapper, res, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, e) -> writeError(mapper, res, 403, "FORBIDDEN")))
                .build();
    }

    private void writeError(JsonMapper mapper, jakarta.servlet.http.HttpServletResponse response,
                            int status, String code) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write(mapper.writeValueAsString(
                new Models.ErrorBody(code, "Access is not authorized.", MDC.get("request_id"))));
    }
}
