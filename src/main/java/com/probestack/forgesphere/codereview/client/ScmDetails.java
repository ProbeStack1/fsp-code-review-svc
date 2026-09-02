package com.probestack.forgesphere.codereview.client;

import java.util.List;

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
        String owner,               // GitHub org / user (scm.orgUser)
        String token,               // decrypted PAT — transient, never stored
        List<TeamRef> reviewTeams,  // every team the CI/CD SCM step marked for code review
        Integer minApprovals,       // CI/CD-configured minimum approving reviews (null → service default)
        String sourceBranch,        // default strategy branch tagged "dev"
        String targetBranch,        // default strategy branch tagged "merge"
        String mergeMethod          // github merge method: merge | squash | rebase
) {
    public boolean hasReviewTeam() {
        return reviewTeams != null && !reviewTeams.isEmpty();
    }

    /** One marked code-review team. */
    public record TeamRef(Long id, String slug, String name) {
        public String displayName() {
            return name != null && !name.isBlank() ? name : slug;
        }
    }
}
