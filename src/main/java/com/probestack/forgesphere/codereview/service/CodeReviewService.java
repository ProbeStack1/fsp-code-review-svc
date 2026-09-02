package com.probestack.forgesphere.codereview.service;

import com.probestack.forgesphere.codereview.client.CicdConfigClient;
import com.probestack.forgesphere.codereview.client.GitHubClient;
import com.probestack.forgesphere.codereview.client.ScmDetails;
import com.probestack.forgesphere.codereview.document.CodeReviewRecord;
import com.probestack.forgesphere.codereview.document.CodeReviewRecord.CheckRun;
import com.probestack.forgesphere.codereview.document.CodeReviewRecord.ReviewComment;
import com.probestack.forgesphere.codereview.document.CodeReviewRecord.ReviewDecision;
import com.probestack.forgesphere.codereview.document.CodeReviewRecord.TimelineEvent;
import com.probestack.forgesphere.codereview.exception.ResourceNotFoundException;
import com.probestack.forgesphere.codereview.model.CreatePullRequestRequest;
import com.probestack.forgesphere.codereview.repository.CodeReviewRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the GitHub-native review lifecycle: create a PR, request the
 * CI/CD-selected review team, then keep a local mirror of GitHub's review /
 * comment / check / merge state that the UI reads. State is refreshed on
 * every read (reconcile-on-open) — no webhook.
 */
@Slf4j
@Service
public class CodeReviewService {

    // review lifecycle
    private static final String PENDING_REVIEW = "PENDING_REVIEW";
    private static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";
    private static final String APPROVED = "APPROVED";
    private static final String CLOSED = "CLOSED";
    private static final String MERGED = "MERGED";
    private static final Set<String> TERMINAL = Set.of(MERGED, CLOSED);

    // merge lifecycle
    private static final String NOT_READY = "NOT_READY";
    private static final String READY_TO_MERGE = "READY_TO_MERGE";
    private static final String MERGING = "MERGING";
    private static final String MERGE_FAILED = "MERGE_FAILED";

    /**
     * GitHub {@code mergeable_state} values that still allow a merge. {@code clean}
     * is all-green; {@code unstable} means only non-required checks are failing/pending
     * (GitHub itself keeps the merge button enabled); {@code has_hooks} is clean with
     * pre-receive hooks configured. Everything else — {@code dirty}, {@code blocked},
     * {@code behind}, {@code draft}, {@code unknown} — is not mergeable from here.
     */
    private static final Set<String> MERGEABLE_STATES = Set.of("clean", "unstable", "has_hooks");

    private final CodeReviewRecordRepository repository;
    private final CicdConfigClient cicdConfigClient;
    private final GitHubClient gitHubClient;
    private final int defaultMinApprovals;
    private final Set<String> adminRoles;

    public CodeReviewService(CodeReviewRecordRepository repository,
                             CicdConfigClient cicdConfigClient,
                             GitHubClient gitHubClient,
                             @Value("${code-review.default-min-approvals:1}") int defaultMinApprovals,
                             @Value("${code-review.admin-roles:ADMIN,ORG_ADMIN,OWNER,SUPER_ADMIN}") String adminRoles) {
        this.repository = repository;
        this.cicdConfigClient = cicdConfigClient;
        this.gitHubClient = gitHubClient;
        this.defaultMinApprovals = Math.max(1, defaultMinApprovals);
        this.adminRoles = Arrays.stream(adminRoles.split(","))
                .map(s -> s.trim().toUpperCase()).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    // ── create ─────────────────────────────────────────────────────────

    public CodeReviewRecord createPullRequest(String microserviceId, String userEmail, String userRole,
                                              CreatePullRequestRequest req) {
        requireEmail(userEmail);
        String assetType = req.getAssetType() != null && !req.getAssetType().isBlank()
                ? req.getAssetType().trim().toUpperCase() : "MICROSERVICE";

        repository.findFirstByMicroserviceIdAndReviewStatusNotInOrderByCreatedAtDesc(
                        microserviceId, new ArrayList<>(TERMINAL))
                .ifPresent(open -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "An open pull request (#" + open.getPullRequestNumber()
                                    + ") already exists for this microservice. Merge or close it first.");
                });

        ScmDetails scm = cicdConfigClient.fetch(req.getCicdConfigId(), assetType, userEmail);
        String owner = scm.owner();
        String repo = req.getRepoName().trim();
        String source = firstNonBlank(req.getSourceBranch(), scm.sourceBranch());
        String target = firstNonBlank(req.getTargetBranch(), scm.targetBranch());

        if (isBlank(source) || source.contains("*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No concrete source branch is configured. Set the development branch in the CI/CD "
                            + "branching strategy, or pass sourceBranch explicitly.");
        }
        if (isBlank(target) || target.contains("*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No concrete target branch is configured. Set the merge-to branch in the CI/CD "
                            + "branching strategy, or pass targetBranch explicitly.");
        }
        if (source.equals(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source and target branch are the same.");
        }

        String title = firstNonBlank(req.getTitle(), "Merge " + source + " into " + target);
        String body = req.getDescription() != null ? req.getDescription()
                : "Opened from ForgeSphere Code Review.";

        Map<String, Object> ghPr = gitHubClient.createPullRequest(owner, repo, scm.token(), title, source, target, body);
        int prNumber = ((Number) ghPr.get("number")).intValue();

        Instant now = Instant.now();
        CodeReviewRecord rec = new CodeReviewRecord();
        rec.setMicroserviceId(microserviceId);
        rec.setCicdConfigId(req.getCicdConfigId());
        rec.setAssetType(assetType);
        rec.setRepositoryOwner(owner);
        rec.setRepositoryName(repo);
        rec.setSourceBranch(source);
        rec.setTargetBranch(target);
        rec.setMergeMethod(scm.mergeMethod());
        rec.setPullRequestNumber(prNumber);
        rec.setPullRequestUrl(str(ghPr.get("html_url")));
        rec.setPullRequestState("OPEN");
        rec.setHeadSha(nestedString(ghPr, "head", "sha"));
        List<ScmDetails.TeamRef> teams = scm.reviewTeams() != null ? scm.reviewTeams() : List.of();
        rec.setReviewTeams(teams.stream()
                .map(t -> new CodeReviewRecord.ReviewTeamRef(t.id(), t.slug(), t.name()))
                .collect(Collectors.toList()));
        rec.setMinApprovals(resolveMinApprovals(scm.minApprovals(), req.getMinApprovals()));
        rec.setReviewStatus(PENDING_REVIEW);
        rec.setMergeStatus(NOT_READY);
        rec.setCreatedBy(userEmail);
        rec.setCreatedByRole(userRole);
        rec.setCreatedAt(now);
        rec.setUpdatedAt(now);
        addEvent(rec, "PULL_REQUEST_CREATED", userEmail,
                "PR #" + prNumber + "  " + source + " → " + target, now);

        requestTeamReview(rec, scm);

        CodeReviewRecord saved = repository.save(rec);

        // One best-effort sync so the first render already has mergeable/checks.
        try {
            reconcile(saved, scm);
            saved = repository.save(saved);
        } catch (RuntimeException e) {
            log.warn("Post-create sync for PR #{} failed (will retry on next read): {}", prNumber, e.getMessage());
        }
        return saved;
    }

    /** Request a review from every team the CI/CD SCM step marked. Partial success is fine. */
    private void requestTeamReview(CodeReviewRecord rec, ScmDetails scm) {
        List<ScmDetails.TeamRef> teams = scm.reviewTeams();
        if (teams == null || teams.isEmpty()) {
            rec.setTeamReviewRequested(false);
            rec.setTeamReviewNote("No review team is configured in CI/CD. Add reviewers manually on the pull request.");
            addEvent(rec, "NO_REVIEW_TEAM", null, rec.getTeamReviewNote(), Instant.now());
            return;
        }
        List<String> ok = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (ScmDetails.TeamRef team : teams) {
            try {
                gitHubClient.requestTeamReviewers(rec.getRepositoryOwner(), rec.getRepositoryName(),
                        scm.token(), rec.getPullRequestNumber(), team.slug());
                ok.add(team.displayName());
            } catch (ResponseStatusException e) {
                failed.add(team.slug() + " (" + e.getReason() + ")");
                log.warn("Team-reviewer request failed for PR #{} team {}: {}",
                        rec.getPullRequestNumber(), team.slug(), e.getReason());
            }
        }
        rec.setTeamReviewRequested(!ok.isEmpty());
        if (!ok.isEmpty()) {
            addEvent(rec, "TEAM_REVIEW_REQUESTED", null,
                    "Requested review from team" + (ok.size() > 1 ? "s " : " ") + String.join(", ", ok),
                    Instant.now());
        }
        if (!failed.isEmpty()) {
            rec.setTeamReviewNote("Could not assign: " + String.join("; ", failed));
            addEvent(rec, "TEAM_REVIEW_REQUEST_FAILED", null, rec.getTeamReviewNote(), Instant.now());
        }
    }

    /** CI/CD-configured minimum wins; else the value passed on the request; else the service default. */
    private int resolveMinApprovals(Integer fromConfig, Integer fromRequest) {
        if (fromConfig != null && fromConfig >= 1) return fromConfig;
        if (fromRequest != null && fromRequest >= 1) return fromRequest;
        return defaultMinApprovals;
    }

    // ── read (+ sync) ──────────────────────────────────────────────────

    public CodeReviewRecord getLatest(String microserviceId, String userEmail) {
        requireEmail(userEmail);
        CodeReviewRecord rec = repository.findFirstByMicroserviceIdOrderByCreatedAtDesc(microserviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pull request has been created for this microservice yet."));
        if (!TERMINAL.contains(rec.getReviewStatus())) {
            try {
                ScmDetails scm = cicdConfigClient.fetch(rec.getCicdConfigId(), rec.getAssetType(), userEmail);
                reconcile(rec, scm);
                rec = repository.save(rec);
            } catch (RuntimeException e) {
                // Non-fatal on a read: return the last known state, UI shows lastSyncedAt.
                log.warn("Sync for microservice {} PR #{} failed: {}", microserviceId, rec.getPullRequestNumber(), e.getMessage());
            }
        }
        return rec;
    }

    public List<CodeReviewRecord> history(String microserviceId) {
        return repository.findByMicroserviceIdOrderByCreatedAtDesc(microserviceId);
    }

    // ── merge ──────────────────────────────────────────────────────────

    public CodeReviewRecord merge(String microserviceId, String userEmail, String userRole) {
        requireEmail(userEmail);
        CodeReviewRecord rec = repository.findFirstByMicroserviceIdOrderByCreatedAtDesc(microserviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pull request has been created for this microservice yet."));

        if (TERMINAL.contains(rec.getReviewStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This pull request is already " + rec.getReviewStatus().toLowerCase() + ".");
        }
        boolean isOwner = userEmail.equalsIgnoreCase(rec.getCreatedBy());
        if (!isOwner && !isOrgAdmin(userRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the developer who raised this pull request, or an org admin, can merge it.");
        }

        ScmDetails scm = cicdConfigClient.fetch(rec.getCicdConfigId(), rec.getAssetType(), userEmail);
        reconcile(rec, scm);

        if (TERMINAL.contains(rec.getReviewStatus())) {
            repository.save(rec);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This pull request is already " + rec.getReviewStatus().toLowerCase() + ".");
        }
        if (CHANGES_REQUESTED.equals(rec.getReviewStatus())) {
            repository.save(rec);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Changes have been requested on this pull request. Resolve them before merging.");
        }
        if (!APPROVED.equals(rec.getReviewStatus())) {
            repository.save(rec);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Waiting for approvals (" + rec.getApprovedBy().size() + "/" + rec.getMinApprovals() + ").");
        }
        String mergeableState = defaultString(rec.getMergeableState(), "unknown").toLowerCase();
        if (!MERGEABLE_STATES.contains(mergeableState)) {
            repository.save(rec);
            throw new ResponseStatusException(HttpStatus.CONFLICT, mergeBlockReason(mergeableState));
        }

        rec.setMergeStatus(MERGING);
        addEvent(rec, "MERGE_REQUESTED", userEmail, null, Instant.now());
        repository.save(rec);

        try {
            Map<String, Object> res = gitHubClient.mergePullRequest(
                    rec.getRepositoryOwner(), rec.getRepositoryName(), scm.token(),
                    rec.getPullRequestNumber(), rec.getMergeMethod());
            boolean merged = Boolean.TRUE.equals(res.get("merged"));
            Instant now = Instant.now();
            if (merged) {
                rec.setMergeStatus(MERGED);
                rec.setReviewStatus(MERGED);
                rec.setPullRequestState("MERGED");
                rec.setMergedVia("PLATFORM");
                rec.setMergedBy(userEmail);
                rec.setMergedAt(now);
                rec.setMergeCommitSha(str(res.get("sha")));
                rec.setMergeError(null);
                addEvent(rec, "MERGED_VIA_PLATFORM", userEmail, str(res.get("message")), now);
                repository.save(rec);
                return rec;
            }
            rec.setMergeStatus(MERGE_FAILED);
            rec.setMergeError(defaultString(str(res.get("message")), "GitHub did not merge the pull request."));
            addEvent(rec, "MERGE_FAILED", userEmail, rec.getMergeError(), now);
            repository.save(rec);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, rec.getMergeError());
        } catch (ResponseStatusException e) {
            if (!MERGE_FAILED.equals(rec.getMergeStatus())) {
                rec.setMergeStatus(MERGE_FAILED);
                rec.setMergeError(e.getReason());
                addEvent(rec, "MERGE_FAILED", userEmail, e.getReason(), Instant.now());
                repository.save(rec);
            }
            throw e;
        }
    }

    // ── close (without merging) ────────────────────────────────────────

    public CodeReviewRecord close(String microserviceId, String userEmail, String userRole) {
        requireEmail(userEmail);
        CodeReviewRecord rec = repository.findFirstByMicroserviceIdOrderByCreatedAtDesc(microserviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pull request has been created for this microservice yet."));

        if (TERMINAL.contains(rec.getReviewStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This pull request is already " + rec.getReviewStatus().toLowerCase() + ".");
        }
        boolean isOwner = userEmail.equalsIgnoreCase(rec.getCreatedBy());
        if (!isOwner && !isOrgAdmin(userRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the developer who raised this pull request, or an org admin, can close it.");
        }

        ScmDetails scm = cicdConfigClient.fetch(rec.getCicdConfigId(), rec.getAssetType(), userEmail);
        try {
            gitHubClient.closePullRequest(rec.getRepositoryOwner(), rec.getRepositoryName(),
                    scm.token(), rec.getPullRequestNumber());
        } catch (ResponseStatusException e) {
            throw e;
        }
        Instant now = Instant.now();
        rec.setReviewStatus(CLOSED);
        rec.setMergeStatus(NOT_READY);
        rec.setPullRequestState("CLOSED");
        addEvent(rec, "CLOSED_VIA_PLATFORM", userEmail, "Pull request closed without merging", now);
        rec.setLastSyncedAt(now);
        rec.setUpdatedAt(now);
        return repository.save(rec);
    }

    // ── comment ───────────────────────────────────────────────────────

    /** Post a conversation comment on the current PR as the calling user, then sync it back. */
    public CodeReviewRecord addComment(String microserviceId, String userEmail, String userRole, String body) {
        requireEmail(userEmail);
        if (body == null || body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comment body is required.");
        }
        CodeReviewRecord rec = repository.findFirstByMicroserviceIdOrderByCreatedAtDesc(microserviceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pull request has been created for this microservice yet."));
        if (MERGED.equals(rec.getReviewStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This pull request is already merged.");
        }

        ScmDetails scm = cicdConfigClient.fetch(rec.getCicdConfigId(), rec.getAssetType(), userEmail);
        gitHubClient.addIssueComment(rec.getRepositoryOwner(), rec.getRepositoryName(),
                scm.token(), rec.getPullRequestNumber(), body.strip());

        Instant now = Instant.now();
        addEvent(rec, "COMMENT_ADDED", userEmail, body.strip(), now);
        rec.setUpdatedAt(now);
        try {
            reconcile(rec, scm);
        } catch (RuntimeException e) {
            log.warn("Post-comment sync for PR #{} failed: {}", rec.getPullRequestNumber(), e.getMessage());
        }
        return repository.save(rec);
    }

    private static String mergeBlockReason(String mergeableState) {
        return switch (mergeableState) {
            case "dirty" -> "This pull request has merge conflicts with the target branch. "
                    + "Resolve the conflicts on GitHub, then retry.";
            case "behind" -> "The source branch is out of date with the target branch. "
                    + "Update it on GitHub, then retry.";
            case "blocked" -> "Branch protection is blocking this merge — a required review or status "
                    + "check is still missing.";
            case "draft" -> "This pull request is still a draft. Mark it ready for review first.";
            case "unknown" -> "GitHub is still computing whether this pull request can be merged. "
                    + "Try again in a few seconds.";
            default -> "GitHub reports this pull request is not mergeable (state: " + mergeableState + ").";
        };
    }

    // ── reconcile ──────────────────────────────────────────────────────

    private void reconcile(CodeReviewRecord rec, ScmDetails scm) {
        String owner = rec.getRepositoryOwner();
        String repo = rec.getRepositoryName();
        String token = scm.token();
        int prNumber = rec.getPullRequestNumber();

        Map<String, Object> pr = gitHubClient.getPullRequest(owner, repo, token, prNumber);
        boolean merged = Boolean.TRUE.equals(pr.get("merged"));
        String ghState = str(pr.get("state"));
        rec.setMergeable(pr.get("mergeable") instanceof Boolean b ? b : null);
        rec.setMergeableState(str(pr.get("mergeable_state")));
        String headSha = nestedString(pr, "head", "sha");
        if (headSha != null) rec.setHeadSha(headSha);

        // reviews
        Set<String> knownReviewIds = rec.getReviews().stream()
                .map(ReviewDecision::getGithubReviewId).filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
        List<ReviewDecision> decisions = new ArrayList<>();
        for (Map<String, Object> raw : gitHubClient.listReviews(owner, repo, token, prNumber)) {
            ReviewDecision d = new ReviewDecision();
            d.setGithubReviewId(str(raw.get("id")));
            d.setReviewerLogin(nestedString(raw, "user", "login"));
            d.setReviewerName(d.getReviewerLogin());
            d.setState(str(raw.get("state")));
            d.setBody(str(raw.get("body")));
            d.setSubmittedAt(parseInstant(raw.get("submitted_at")));
            decisions.add(d);
            if (d.getGithubReviewId() != null && !knownReviewIds.contains(d.getGithubReviewId())) {
                addEvent(rec, "REVIEW_" + defaultString(d.getState(), "SUBMITTED"),
                        d.getReviewerLogin(), d.getBody(), d.getSubmittedAt());
            }
        }
        decisions.sort(Comparator.comparing(ReviewDecision::getSubmittedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        rec.setReviews(decisions);

        Map<String, String> effective = effectiveStates(decisions);
        List<String> approved = effective.entrySet().stream()
                .filter(e -> APPROVED.equals(e.getValue())).map(Map.Entry::getKey).sorted().toList();
        rec.setApprovedBy(new ArrayList<>(approved));
        boolean changesRequested = effective.containsValue(CHANGES_REQUESTED);

        // comments
        List<ReviewComment> comments = new ArrayList<>();
        for (Map<String, Object> raw : gitHubClient.listIssueComments(owner, repo, token, prNumber)) {
            comments.add(comment(raw, null, null));
        }
        for (Map<String, Object> raw : gitHubClient.listReviewComments(owner, repo, token, prNumber)) {
            comments.add(comment(raw, str(raw.get("path")),
                    raw.get("line") instanceof Number n ? n.intValue() : null));
        }
        comments.sort(Comparator.comparing(ReviewComment::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        rec.setComments(comments);

        // checks
        List<CheckRun> checks = new ArrayList<>();
        for (Map<String, Object> raw : gitHubClient.listCheckRuns(owner, repo, token, rec.getHeadSha())) {
            CheckRun c = new CheckRun();
            c.setName(str(raw.get("name")));
            c.setStatus(str(raw.get("status")));
            c.setConclusion(str(raw.get("conclusion")));
            c.setCompletedAt(parseInstant(raw.get("completed_at")));
            checks.add(c);
        }
        rec.setChecks(checks);

        // status
        String previousStatus = rec.getReviewStatus();
        String reviewStatus;
        if (merged) {
            reviewStatus = MERGED;
        } else if ("closed".equalsIgnoreCase(ghState)) {
            reviewStatus = CLOSED;
        } else if (changesRequested) {
            reviewStatus = CHANGES_REQUESTED;
        } else if (approved.size() >= rec.getMinApprovals()) {
            reviewStatus = APPROVED;
        } else {
            reviewStatus = PENDING_REVIEW;
        }
        rec.setReviewStatus(reviewStatus);
        rec.setPullRequestState(merged ? "MERGED" : ("closed".equalsIgnoreCase(ghState) ? "CLOSED" : "OPEN"));

        if (merged) {
            rec.setMergeStatus(MERGED);
            if (rec.getMergedAt() == null) {
                rec.setMergedVia("GITHUB");
                rec.setMergedBy(defaultString(nestedString(pr, "merged_by", "login"), "unknown"));
                rec.setMergedAt(parseInstant(pr.get("merged_at")));
                rec.setMergeCommitSha(str(pr.get("merge_commit_sha")));
                addEvent(rec, "MERGED_EXTERNALLY", rec.getMergedBy(), "Merged on GitHub", rec.getMergedAt());
            }
        } else if ("closed".equalsIgnoreCase(ghState)) {
            rec.setMergeStatus(NOT_READY);
            if (rec.getEvents().stream().noneMatch(e -> "CLOSED_EXTERNALLY".equals(e.getType()))) {
                addEvent(rec, "CLOSED_EXTERNALLY", null,
                        "Pull request was closed on GitHub without merging", Instant.now());
            }
        } else if (APPROVED.equals(reviewStatus)
                && MERGEABLE_STATES.contains(defaultString(rec.getMergeableState(), "").toLowerCase())) {
            rec.setMergeStatus(READY_TO_MERGE);
        } else if (!MERGING.equals(rec.getMergeStatus())) {
            rec.setMergeStatus(NOT_READY);
        }

        if (!Objects.equals(previousStatus, reviewStatus)) {
            addEvent(rec, "REVIEW_STATUS_CHANGED", null,
                    defaultString(previousStatus, "—") + " → " + reviewStatus, Instant.now());
        }

        Instant now = Instant.now();
        rec.setLastSyncedAt(now);
        rec.setUpdatedAt(now);
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Latest APPROVED / CHANGES_REQUESTED / DISMISSED per reviewer (COMMENTED does not change effective state). */
    private static Map<String, String> effectiveStates(List<ReviewDecision> decisionsSortedAsc) {
        Map<String, String> latest = new LinkedHashMap<>();
        for (ReviewDecision d : decisionsSortedAsc) {
            String state = d.getState();
            if (d.getReviewerLogin() == null) continue;
            if (APPROVED.equals(state) || CHANGES_REQUESTED.equals(state) || "DISMISSED".equals(state)) {
                latest.put(d.getReviewerLogin(), state);
            }
        }
        return latest;
    }

    private static ReviewComment comment(Map<String, Object> raw, String path, Integer line) {
        ReviewComment c = new ReviewComment();
        c.setGithubCommentId(str(raw.get("id")));
        c.setAuthor(nestedString(raw, "user", "login"));
        c.setBody(str(raw.get("body")));
        c.setPath(path);
        c.setLine(line);
        c.setCreatedAt(parseInstant(raw.get("created_at")));
        return c;
    }

    private boolean isOrgAdmin(String role) {
        return role != null && adminRoles.contains(role.trim().toUpperCase());
    }

    private static void addEvent(CodeReviewRecord rec, String type, String actor, String detail, Instant when) {
        rec.getEvents().add(new TimelineEvent(type, actor, trim(detail), when != null ? when : Instant.now()));
    }

    private static void requireEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-User-Email header is required.");
        }
    }

    @SuppressWarnings("unchecked")
    private static String nestedString(Map<String, Object> map, String key, String childKey) {
        Object child = map == null ? null : map.get(key);
        if (child instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get(childKey);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }

    private static Instant parseInstant(Object v) {
        if (v == null) return null;
        try {
            return Instant.parse(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() > 2000 ? t.substring(0, 2000) : t;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String defaultString(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
