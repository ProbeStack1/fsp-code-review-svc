package com.probestack.forgesphere.codereview.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body for POST /v1/api/code-reviews/{microserviceId}/pull-request.
 *
 * <p>{@code cicdConfigId} is the onboarding id the CI/CD config is stored
 * under. {@code repoName} is the GitHub repository (no owner — the owner
 * comes from the CI/CD SCM config). Branch names default to the CI/CD
 * default branching strategy; pass them only to override.</p>
 */
@Data
public class CreatePullRequestRequest {

    @NotBlank
    private String cicdConfigId;

    @NotBlank
    private String repoName;

    private String assetType;        // defaults to MICROSERVICE

    private String sourceBranch;     // override; else strategy "dev" branch
    private String targetBranch;     // override; else strategy "merge" branch

    private String title;            // else "Merge <source> into <target>"
    private String description;

    /** Approvals required before the PR is mergeable from the platform. Optional; defaults to 1. */
    private Integer minApprovals;
}
