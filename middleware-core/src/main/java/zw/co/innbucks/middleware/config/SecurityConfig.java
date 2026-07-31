package zw.co.innbucks.middleware.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import zw.co.innbucks.middleware.auth.jwt.InnbucksJwtAuthenticationConverter;
import zw.co.innbucks.middleware.common.country.CountryProperties;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CountryProperties.class)
public class SecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000L))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/auth/dev/**").permitAll()
                        .requestMatchers("/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .requestMatchers("/auth/otp/**").permitAll()
                        .requestMatchers("/auth/pin/**").permitAll()
                        // Public onboarding: Idempotency-Key required + per-IP
                        // rate limited; everything money-touching stays behind
                        // the JWT (anyRequest below).
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/register").permitAll()
                        // Internal core-event webhook (three-files-must-agree):
                        // (1) the receiving controller enforces its shared
                        // token constant-time — without THIS permitAll the JWT
                        // filter 401s the core's POST before that check runs;
                        // (3) the edge must never route /internal/** — the
                        // core reaches it over the private cell network only
                        // (nginx deny pinned in the runbook).
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/core-events/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new InnbucksJwtAuthenticationConverter())));
        return http.build();
    }
}
