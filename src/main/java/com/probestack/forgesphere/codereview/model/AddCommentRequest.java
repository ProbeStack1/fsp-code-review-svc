package com.probestack.forgesphere.codereview.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for POST /v1/api/code-reviews/{microserviceId}/comment.
 *
 * <p>Posts a conversation comment on the current pull request as the calling
 * user (via the CI/CD SCM token). The comment is mirrored back into the
 * record on the next reconcile.</p>
 */
@Data
public class AddCommentRequest {

    @NotBlank
    @Size(max = 60_000)
    private String body;
}
