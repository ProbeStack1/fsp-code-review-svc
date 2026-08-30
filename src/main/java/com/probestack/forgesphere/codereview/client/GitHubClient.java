package com.probestack.forgesphere.codereview.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over the GitHub REST API — the only place this service talks
 * to GitHub. Every call takes the SCM token explicitly (fetched per-request
 * from CI/CD config, never stored). GitHub 4xx bodies are unwrapped to their
 * {@code message} so the UI shows GitHub's own words.
 */
@Slf4j
@Component
public class GitHubClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiBaseUrl;

    public GitHubClient(RestTemplate restTemplate,
                        ObjectMapper objectMapper,
                        @Value("${github.api.base-url:https://api.github.com}") String apiBaseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl.substring(0, apiBaseUrl.length() - 1) : apiBaseUrl;
    }

    // ── Pull requests ───────────────────────────────────────────────────

    /** POST /repos/{owner}/{repo}/pulls */
    public Map<String, Object> createPullRequest(String owner, String repo, String token,
                                                 String title, String head, String base, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("head", head);
        payload.put("base", base);
        if (body != null) payload.put("body", body);
        return post("/repos/" + owner + "/" + repo + "/pulls", token, payload, "create pull request");
    }

    /** POST /repos/{owner}/{repo}/pulls/{number}/requested_reviewers */
    public void requestTeamReviewers(String owner, String repo, String token, int prNumber, String teamSlug) {
        Map<String, Object> payload = Map.of("team_reviewers", List.of(teamSlug));
        post("/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/requested_reviewers",
                token, payload, "request team reviewers");
    }

    /** GET /repos/{owner}/{repo}/pulls/{number} */
    public Map<String, Object> getPullRequest(String owner, String repo, String token, int prNumber) {
        return getObject("/repos/" + owner + "/" + repo + "/pulls/" + prNumber, token, "fetch pull request");
    }

    /** GET /repos/{owner}/{repo}/pulls/{number}/reviews (paginated) */
    public List<Map<String, Object>> listReviews(String owner, String repo, String token, int prNumber) {
        return getPaged("/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/reviews", token, "list reviews");
    }

    /** GET /repos/{owner}/{repo}/pulls/{number}/comments — inline review comments (paginated) */
    public List<Map<String, Object>> listReviewComments(String owner, String repo, String token, int prNumber) {
        return getPaged("/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/comments", token, "list review comments");
    }

    /** GET /repos/{owner}/{repo}/issues/{number}/comments — conversation comments (paginated) */
    public List<Map<String, Object>> listIssueComments(String owner, String repo, String token, int prNumber) {
        return getPaged("/repos/" + owner + "/" + repo + "/issues/" + prNumber + "/comments", token, "list comments");
    }

    /** GET /repos/{owner}/{repo}/commits/{ref}/check-runs */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listCheckRuns(String owner, String repo, String token, String ref) {
        if (ref == null || ref.isBlank()) return List.of();
        Map<String, Object> wrapper = getObject(
                "/repos/" + owner + "/" + repo + "/commits/" + ref + "/check-runs", token, "list check runs");
        Object runs = wrapper.get("check_runs");
        return runs instanceof List ? (List<Map<String, Object>>) runs : List.of();
    }

    /** PUT /repos/{owner}/{repo}/pulls/{number}/merge */
    public Map<String, Object> mergePullRequest(String owner, String repo, String token,
                                                int prNumber, String mergeMethod) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (mergeMethod != null && !mergeMethod.isBlank()) payload.put("merge_method", mergeMethod);
        return put("/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/merge", token, payload, "merge pull request");
    }

    // ── transport ──────────────────────────────────────────────────────

    private Map<String, Object> post(String path, String token, Object body, String action) {
        return exchangeObject(HttpMethod.POST, path, token, body, action);
    }

    private Map<String, Object> put(String path, String token, Object body, String action) {
        return exchangeObject(HttpMethod.PUT, path, token, body, action);
    }

    private Map<String, Object> getObject(String path, String token, String action) {
        return exchangeObject(HttpMethod.GET, path, token, null, action);
    }

    private Map<String, Object> exchangeObject(HttpMethod method, String path, String token, Object body, String action) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    apiBaseUrl + path, method, new HttpEntity<>(body, headers(token)),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            Map<String, Object> out = response.getBody();
            return out != null ? out : new LinkedHashMap<>();
        } catch (HttpStatusCodeException e) {
            throw translate(e, action);
        } catch (RestClientException e) {
            log.error("GitHub call failed ({}): {}", action, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach GitHub to " + action + ".");
        }
    }

    private List<Map<String, Object>> getPaged(String path, String token, String action) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            String url = apiBaseUrl + path + (path.contains("?") ? "&" : "?") + "per_page=100&page=" + page;
            List<Map<String, Object>> batch;
            try {
                ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                        url, HttpMethod.GET, new HttpEntity<>(headers(token)),
                        new ParameterizedTypeReference<List<Map<String, Object>>>() {});
                batch = response.getBody();
            } catch (HttpStatusCodeException e) {
                throw translate(e, action);
            } catch (RestClientException e) {
                if (page > 1) break;
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach GitHub to " + action + ".");
            }
            if (batch == null || batch.isEmpty()) break;
            all.addAll(batch);
            if (batch.size() < 100) break;
        }
        return all;
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        headers.set(HttpHeaders.USER_AGENT, "ForgeSphere-code-review");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseStatusException translate(HttpStatusCodeException e, String action) {
        int code = e.getStatusCode().value();
        String detail = extractGitHubMessage(e.getResponseBodyAsString());
        log.warn("GitHub {} -> HTTP {} : {}", action, code, detail);
        HttpStatus mapped;
        if (code == 401 || code == 403) {
            mapped = HttpStatus.BAD_REQUEST;
        } else if (code == 404) {
            mapped = HttpStatus.NOT_FOUND;
        } else if (code == 409 || code == 422) {
            mapped = HttpStatus.CONFLICT;
        } else {
            mapped = HttpStatus.BAD_GATEWAY;
        }
        return new ResponseStatusException(mapped, "GitHub could not " + action + ": " + detail);
    }

    private String extractGitHubMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "no details returned";
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            StringBuilder sb = new StringBuilder();
            if (root.hasNonNull("message")) sb.append(root.get("message").asText());
            JsonNode errors = root.get("errors");
            if (errors != null && errors.isArray()) {
                for (JsonNode err : errors) {
                    if (err.hasNonNull("message")) {
                        sb.append(sb.length() > 0 ? " — " : "").append(err.get("message").asText());
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : responseBody;
        } catch (Exception ignored) {
            return responseBody;
        }
    }
}
