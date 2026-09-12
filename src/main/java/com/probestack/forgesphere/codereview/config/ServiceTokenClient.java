package com.probestack.forgesphere.codereview.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Obtains a short-lived {@code probestack_service_access} token from ps-token-issuer-svc so this
 * service can authenticate itself into fsp-cicd-automation-svc's {@code /api/cicd-config/*}
 * endpoint — used by {@link com.probestack.forgesphere.codereview.client.CicdConfigClient} to read
 * a microservice's CI/CD-selected SCM credentials — instead of calling it with no auth at all.
 * <p>
 * A token is bound to one organization — {@link #getToken(String)} takes the calling user's own
 * organization id (from {@link AuthenticatedCaller#organizationId()}, never a fixed/placeholder
 * value) and caches a token per organization, minting a fresh one only when the cached one is
 * empty or within {@link #REFRESH_SKEW} of expiry.
 * <p>
 * Disabled by default ({@code forge.service-token.enabled=false}) — the caller falls back to its
 * prior (unauthenticated) behaviour until the issuer client is provisioned and
 * fsp-cicd-automation-svc enables service-token validation on its own end.
 */
@Component
public class ServiceTokenClient {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenClient.class);
    private static final Duration REFRESH_SKEW = Duration.ofSeconds(30);

    private final boolean enabled;
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String audience;
    private final String scope;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private record CachedToken(String token, Instant expiry) {}

    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public ServiceTokenClient(
            @Value("${forge.service-token.enabled:false}") boolean enabled,
            @Value("${forge.service-token.token-url:https://probestack.io/token-issuer-api/api/v1/service-tokens}") String tokenUrl,
            @Value("${forge.service-token.client-id:}") String clientId,
            @Value("${forge.service-token.client-secret:}") String clientSecret,
            @Value("${forge.service-token.audience:probestack-api}") String audience,
            @Value("${forge.service-token.scope:cicd:config:read}") String scope,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.audience = audience;
        this.scope = scope;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public boolean isEnabled() {
        return enabled && hasText(tokenUrl) && hasText(clientId) && hasText(clientSecret);
    }

    /**
     * A currently-valid service access token bound to {@code organizationId}, minting a fresh one
     * only when the per-organization cache is empty or about to expire. Not called unless
     * {@link #isEnabled()}.
     */
    public String getToken(String organizationId) {
        if (!hasText(organizationId)) {
            throw new IllegalStateException("No organization id available for the service token request");
        }
        String org = organizationId.trim();
        CachedToken cached = cache.get(org);
        if (cached != null && Instant.now().isBefore(cached.expiry().minus(REFRESH_SKEW))) {
            return cached.token();
        }
        return mint(org);
    }

    /** Drop the cached token for this organization so the next {@link #getToken(String)} re-mints. */
    public void invalidate(String organizationId) {
        if (hasText(organizationId)) {
            cache.remove(organizationId.trim());
        }
    }

    private synchronized String mint(String org) {
        // Re-check under the lock — a concurrent caller may have just minted one for this org.
        CachedToken cached = cache.get(org);
        if (cached != null && Instant.now().isBefore(cached.expiry().minus(REFRESH_SKEW))) {
            return cached.token();
        }
        String form = "grant_type=client_credentials"
                + "&audience=" + enc(audience)
                + "&organization_id=" + enc(org)
                + "&scope=" + enc(scope);
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Token issuer returned HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode body = objectMapper.readTree(response.body());
            String token = body.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("Token issuer returned no access_token");
            }
            long expiresIn = body.path("expires_in").asLong(300L);
            cache.put(org, new CachedToken(token, Instant.now().plusSeconds(expiresIn)));
            log.info("Obtained service access token for client={} org={} scope=[{}] ttl={}s",
                    clientId, org, scope, expiresIn);
            return token;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while obtaining a service access token", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to obtain a service access token: " + ex.getMessage(), ex);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
