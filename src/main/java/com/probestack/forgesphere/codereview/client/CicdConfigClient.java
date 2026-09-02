package com.probestack.forgesphere.codereview.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a microservice's CI/CD configuration from fsp-cicd-automation-svc.
 *
 * <p>The CI/CD config is keyed by the onboarding id (the same value
 * fsp-api-development-svc's merge flow passes as {@code microserviceId} on
 * that service's URL), so callers must hand us the onboarding id.</p>
 *
 * <p>The call is made on behalf of the acting user — their {@code X-User-Email}
 * is forwarded, matching how the merge flow reads CI/CD config today.</p>
 *
 * <p>SECURITY: the {@code /cicd-config/{id}/all} response currently includes
 * the decrypted SCM token because the existing merge flow depends on it.
 * That endpoint must move behind a service-to-service credential check
 * before this service ships to production — tracked as plan item "service
 * auth".</p>
 */
@Slf4j
@Component
public class CicdConfigClient {

    private final RestTemplate restTemplate;
    private final String cicdBaseUrl;

    public CicdConfigClient(RestTemplate restTemplate,
                            @Value("${cicd.service.url}") String cicdBaseUrl) {
        this.restTemplate = restTemplate;
        this.cicdBaseUrl = cicdBaseUrl;
    }

    @SuppressWarnings("unchecked")
    public ScmDetails fetch(String cicdConfigId, String assetType, String userEmail) {
        if (userEmail == null || userEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Email is required.");
        }
        // MCP servers reuse the microservice pipeline profile — there is no
        // separate "MCP" CI/CD config (mirrors fsp-api-development-svc).
        if ("MCP".equalsIgnoreCase(assetType)) {
            assetType = "MICROSERVICE";
        }
        String url = cicdBaseUrl + "/" + cicdConfigId + "/all?filtered=true";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Email", userEmail);

        Map<String, Object> config;
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            config = response.getBody();
        } catch (RestClientException e) {
            log.error("Failed to load CI/CD config {}: {}", cicdConfigId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not load CI/CD configuration for this application.");
        }
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CI/CD configuration for this application is empty.");
        }

        Map<String, Object> pipelineConfigs = asMap(config.get("pipelineConfigs"));
        Map<String, Object> pipeline = pipelineConfigs == null ? null : asMap(pipelineConfigs.get(assetType));
        if (pipeline == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No pipeline configuration found for asset type " + assetType
                            + ". Configure CI/CD for this application first.");
        }
        Map<String, Object> scm = asMap(pipeline.get("scm"));
        if (scm == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No source-control configuration found. Set up SCM in the CI/CD Pipeline step first.");
        }

        String provider = str(scm.get("provider"));
        if (provider != null && !provider.equalsIgnoreCase("github")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Code review currently supports GitHub only (this application's SCM is " + provider + ").");
        }
        String owner = str(scm.get("orgUser"));
        String token = str(scm.get("token"));
        if (isBlank(owner) || isBlank(token)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The CI/CD SCM configuration is missing the GitHub organization or token.");
        }

        String mergeMethod = "squash";
        String sourceBranch = null;
        String targetBranch = null;
        List<Map<String, Object>> strategies = asList(config.get("strategies"));
        if (strategies != null && !strategies.isEmpty()) {
            Map<String, Object> strategy = strategies.stream()
                    .filter(s -> Boolean.TRUE.equals(s.get("isDefault")))
                    .findFirst()
                    .orElse(strategies.get(0));
            mergeMethod = normalizeMergeMethod(str(strategy.get("mergeStrategy")));
            List<Map<String, Object>> branches = asList(strategy.get("branches"));
            if (branches != null) {
                for (Map<String, Object> branch : branches) {
                    String tag = str(branch.get("tag"));
                    if ("dev".equalsIgnoreCase(tag)) sourceBranch = str(branch.get("name"));
                    if ("merge".equalsIgnoreCase(tag)) targetBranch = str(branch.get("name"));
                }
            }
        }

        return new ScmDetails(
                owner,
                token,
                parseReviewTeams(scm),
                asInteger(scm.get("minApprovals")),
                sourceBranch,
                targetBranch,
                mergeMethod);
    }

    /** The {@code scm.reviewTeams[]} the CI/CD SCM step marked as code reviewers. */
    private static List<ScmDetails.TeamRef> parseReviewTeams(Map<String, Object> scm) {
        List<ScmDetails.TeamRef> out = new ArrayList<>();
        List<Map<String, Object>> raw = asList(scm.get("reviewTeams"));
        if (raw != null) {
            for (Map<String, Object> t : raw) {
                if (t == null) continue;
                String slug = str(t.get("slug"));
                if (isBlank(slug)) continue;
                boolean dup = out.stream().anyMatch(e -> slug.equalsIgnoreCase(e.slug()));
                if (!dup) out.add(new ScmDetails.TeamRef(asLong(t.get("id")), slug, str(t.get("name"))));
            }
        }
        return out;
    }

    // ── mapping helpers ──────────────────────────────────────────────────

    private static String normalizeMergeMethod(String raw) {
        if (raw == null) return "squash";
        String upper = raw.toUpperCase();
        if (upper.contains("REBASE")) return "rebase";
        if (upper.contains("SQUASH")) return "squash";
        if (upper.contains("MERGE")) return "merge";
        return "squash";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static Integer asInteger(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
