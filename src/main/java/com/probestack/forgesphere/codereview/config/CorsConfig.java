package com.probestack.forgesphere.codereview.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/** Mirrors fsp-cicd-automation-svc's CORS: credentials on, the same header + origin allow-list. */
@Configuration
public class CorsConfig {

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final List<String> ALLOWED_HEADERS = List.of(
            "Authorization",
            "Content-Type",
            "Accept",
            "Origin",
            "X-Requested-With",
            "X-Organization-Id",
            "X-Partner-Id",
            "X-User-Email",
            "X-User-Id",
            "X-User-Name",
            "X-User-Role",
            "X-Service-Transaction-Id",
            "X-Trace-Id"
    );
    private static final List<String> EXPOSED_HEADERS = List.of("Authorization", "Content-Disposition", "X-Trace-Id");
    private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(
            "https://probestack.io",
            "https://www.probestack.io",
            "https://console.probestack.io",
            "https://support.probestack.io",
            "https://community.probestack.io",
            "https://forgesphere.probestack.io",
            "https://forgefuzz.prbestack.io",
            "https://forgefuzz.probestack.io",
            "https://forgefuzz.com",
            "https://www.forgefuzz.com",
            "https://forgecatalog.probestack.io",
            "https://forgecatalog.com",
            "https://www.forgecatalog.com",
            "https://forgegateway.probestack.io",
            "https://forgeaigateway.probestack.io",
            "https://forgehub.probestack.io/"
    );

    private final List<String> allowedOrigins;
    private final List<String> allowedOriginPatterns;

    public CorsConfig(
            @Value("${probestack.cors.allowed-origins:}") String allowedOrigins,
            @Value("${probestack.cors.allowed-origin-patterns:http://localhost:*,https://localhost:*,http://127.0.0.1:*,https://127.0.0.1:*}") String allowedOriginPatterns) {
        List<String> configuredOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        this.allowedOrigins = configuredOrigins.isEmpty() ? DEFAULT_ALLOWED_ORIGINS : configuredOrigins;
        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toList();
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.setAllowedMethods(ALLOWED_METHODS);
        config.setAllowedHeaders(ALLOWED_HEADERS);
        config.setExposedHeaders(EXPOSED_HEADERS);
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
