package com.probestack.forgesphere.codereview.api;

import com.probestack.forgesphere.codereview.document.CodeReviewRecord;
import com.probestack.forgesphere.codereview.model.AddCommentRequest;
import com.probestack.forgesphere.codereview.model.CreatePullRequestRequest;
import com.probestack.forgesphere.codereview.service.CodeReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GitHub-native code review + merge lifecycle for one microservice.
 *
 * <p>Reviewers approve / reject / comment on GitHub; this service mirrors
 * that state into {@code code_review_records} and lets the developer merge
 * from the platform once GitHub says it is ready.</p>
 */
@RestController
@RequestMapping("/v1/api/code-reviews")
@RequiredArgsConstructor
public class CodeReviewController {

    private static final String USER_EMAIL = "X-User-Email";
    private static final String USER_ROLE = "X-User-Role";

    private final CodeReviewService service;

    /** Raise a PR and request the CI/CD-selected review team. */
    @PostMapping("/{microserviceId}/pull-request")
    public ResponseEntity<CodeReviewRecord> createPullRequest(
            @PathVariable String microserviceId,
            @Valid @RequestBody CreatePullRequestRequest request,
            @RequestHeader(value = USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = USER_ROLE, required = false) String userRole) {
        return ResponseEntity.ok(service.createPullRequest(microserviceId, userEmail, userRole, request));
    }

    /** Latest review record for this microservice, synced from GitHub first. */
    @GetMapping("/{microserviceId}")
    public ResponseEntity<CodeReviewRecord> getLatest(
            @PathVariable String microserviceId,
            @RequestHeader(value = USER_EMAIL, required = false) String userEmail) {
        return ResponseEntity.ok(service.getLatest(microserviceId, userEmail));
    }

    /** Every PR ever raised for this microservice, newest first. */
    @GetMapping("/{microserviceId}/history")
    public ResponseEntity<List<CodeReviewRecord>> history(@PathVariable String microserviceId) {
        return ResponseEntity.ok(service.history(microserviceId));
    }

    /** Merge the current PR (requester or org admin only; re-checks GitHub first). */
    @PostMapping("/{microserviceId}/merge")
    public ResponseEntity<CodeReviewRecord> merge(
            @PathVariable String microserviceId,
            @RequestHeader(value = USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = USER_ROLE, required = false) String userRole) {
        return ResponseEntity.ok(service.merge(microserviceId, userEmail, userRole));
    }

    /** Close the current PR without merging (requester or org admin). Frees a new one to be raised. */
    @PostMapping("/{microserviceId}/close")
    public ResponseEntity<CodeReviewRecord> close(
            @PathVariable String microserviceId,
            @RequestHeader(value = USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = USER_ROLE, required = false) String userRole) {
        return ResponseEntity.ok(service.close(microserviceId, userEmail, userRole));
    }

    /** Post a conversation comment on the current PR (mirrored to GitHub as the calling user). */
    @PostMapping("/{microserviceId}/comment")
    public ResponseEntity<CodeReviewRecord> comment(
            @PathVariable String microserviceId,
            @Valid @RequestBody AddCommentRequest request,
            @RequestHeader(value = USER_EMAIL, required = false) String userEmail,
            @RequestHeader(value = USER_ROLE, required = false) String userRole) {
        return ResponseEntity.ok(service.addComment(microserviceId, userEmail, userRole, request.getBody()));
    }
}
