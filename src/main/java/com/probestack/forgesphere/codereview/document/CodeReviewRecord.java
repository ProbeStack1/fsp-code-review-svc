package com.probestack.forgesphere.codereview.document;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One pull-request review lifecycle. A new record is created every time a
 * developer raises a PR from the Code Review step — an earlier record for
 * the same microservice is never overwritten, so the full history is kept.
 *
 * <p>Nothing secret is stored here: the SCM token is fetched from CI/CD per
 * request and used only in memory.</p>
 */
@Data
@NoArgsConstructor
@Document(collection = "code_review_records")
public class CodeReviewRecord {

    @Id
    private String id;

    /** Resource this review belongs to (record key used by the UI). */
    @Indexed
    private String microserviceId;

    /** Onboarding id — the key CI/CD config is stored under; needed to re-read SCM on every sync. */
    private String cicdConfigId;

    private String assetType;            // MICROSERVICE | APIGEE_PROXY | KONG_GATEWAY_SERVICE

    private String repositoryOwner;
    private String repositoryName;
    private String sourceBranch;
    private String targetBranch;
    private String mergeMethod;          // merge | squash | rebase

    private Integer pullRequestNumber;
    private String pullRequestUrl;
    private String pullRequestState;     // OPEN | CLOSED | MERGED
    private String headSha;

    private Long reviewTeamId;
    private String reviewTeamSlug;
    private String reviewTeamName;
    private boolean teamReviewRequested;
    private String teamReviewNote;       // why the team wasn't/couldn't be requested

    private int minApprovals = 1;

    private String reviewStatus;         // PENDING_REVIEW | CHANGES_REQUESTED | APPROVED | CLOSED | MERGED
    private String mergeStatus;          // NOT_READY | READY_TO_MERGE | MERGING | MERGED | MERGE_FAILED
    private Boolean mergeable;
    private String mergeableState;       // GitHub's mergeable_state (clean, blocked, dirty, behind, ...)

    private String createdBy;
    private String createdByRole;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastSyncedAt;

    private List<String> approvedBy = new ArrayList<>();
    private List<ReviewDecision> reviews = new ArrayList<>();
    private List<ReviewComment> comments = new ArrayList<>();
    private List<CheckRun> checks = new ArrayList<>();

    private String mergedBy;
    private Instant mergedAt;
    private String mergeCommitSha;
    private String mergedVia;            // PLATFORM | GITHUB
    private String mergeError;

    private List<TimelineEvent> events = new ArrayList<>();

    // ── embedded types ─────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    public static class ReviewDecision {
        private String githubReviewId;
        private String reviewerLogin;
        private String reviewerName;
        private String state;            // APPROVED | CHANGES_REQUESTED | COMMENTED | DISMISSED
        private String body;
        private Instant submittedAt;
    }

    @Data
    @NoArgsConstructor
    public static class ReviewComment {
        private String githubCommentId;
        private String author;
        private String body;
        private String path;             // null for conversation comments
        private Integer line;
        private Instant createdAt;
    }

    @Data
    @NoArgsConstructor
    public static class CheckRun {
        private String name;
        private String status;           // queued | in_progress | completed
        private String conclusion;       // success | failure | neutral | cancelled | skipped | timed_out
        private Instant completedAt;
    }

    @Data
    @NoArgsConstructor
    public static class TimelineEvent {
        private String type;
        private String actor;
        private String detail;
        private Instant occurredAt;

        public TimelineEvent(String type, String actor, String detail, Instant occurredAt) {
            this.type = type;
            this.actor = actor;
            this.detail = detail;
            this.occurredAt = occurredAt;
        }
    }
}
