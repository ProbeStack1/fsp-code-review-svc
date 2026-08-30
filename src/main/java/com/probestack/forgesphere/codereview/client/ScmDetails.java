package com.probestack.forgesphere.codereview.client;

/**
 * Transient carrier for the slice of a microservice's CI/CD configuration
 * this service needs on a single request. Fetched fresh from
 * fsp-cicd-automation-svc every time — nothing here is persisted by this
 * service.
 *
 * <p>{@code token} is already decrypted upstream by fsp-cicd-automation-svc;
 * it is used only to call GitHub in-memory and is never written to
 * {@code CodeReviewRecord}, never logged.</p>
 */
public record ScmDetails(
        String owner,           // GitHub org / user (scm.orgUser)
        String token,           // decrypted PAT — transient, never stored
        Long reviewTeamId,
        String reviewTeamSlug,  // what GitHub's requested_reviewers API expects
        String reviewTeamName,
        String sourceBranch,    // default strategy branch tagged "dev"
        String targetBranch,    // default strategy branch tagged "merge"
        String mergeMethod      // github merge method: merge | squash | rebase
) {
    public boolean hasReviewTeam() {
        return reviewTeamSlug != null && !reviewTeamSlug.isBlank();
    }
}
