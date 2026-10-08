package com.funchole.backend.controlplane.config;

import com.funchole.backend.controlplane.security.ApiKeyAuthenticationFilter;
import com.funchole.backend.controlplane.security.JwtAuthenticationFilter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/system/ping",
            "/api/v1/auth/token",
            "/api/v1/auth/google",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/actuator/health",
            "/actuator/health/**",
            // The MCP Streamable HTTP transport (see ApiKeyAuthenticationFilter's
            // own javadoc) dispatches asynchronously, and if that async
            // continuation ever hits an auth failure after the response is
            // already committed, Tomcat's own error-page dispatch to /error
            // must not ALSO be blocked by this same filter chain - that
            // would just replace one denial with a noisier, cascading one.
            "/error"
    };

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationProvider authenticationProvider,
            SecurityProperties securityProperties
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Explicit (not just relying on the STATELESS default) so
                // ApiKeyAuthenticationFilter/JwtAuthenticationFilter's own
                // securityContextRepository.saveContext(...) calls persist
                // into the SAME request-attribute-backed store this filter
                // chain reads from - request attributes, unlike the
                // SecurityContextHolder ThreadLocal, survive a Servlet async
                // dispatch onto a different worker thread (see the MCP
                // Streamable HTTP transport's own async continuation).
                .securityContext(securityContext -> securityContext.securityContextRepository(securityContextRepository()))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/info").permitAll()
                        // Metrics, flyway and the rest of actuator describe the whole
                        // platform, not the caller: operator (bootstrap admin) only.
                        .requestMatchers("/actuator/**").access((authentication, context) -> new AuthorizationDecision(
                                authentication.get().isAuthenticated()
                                        && securityProperties.bootstrapUser().username()
                                                .equalsIgnoreCase(authentication.get().getName())))
                        .anyRequest().authenticated())
                // Explicitly ordered relative to each other (not just both
                // "before UsernamePasswordAuthenticationFilter") so the API
                // key check always runs first, deterministically - see
                // ApiKeyAuthenticationFilter/JwtAuthenticationFilter's own
                // "already authenticated?" guards for why both being tried
                // must not be ambiguous about which one wins.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new RequestAttributeSecurityContextRepository();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
