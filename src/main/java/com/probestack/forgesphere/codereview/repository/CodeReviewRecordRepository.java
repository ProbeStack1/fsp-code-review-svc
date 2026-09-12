package com.probestack.forgesphere.codereview.repository;

import com.probestack.forgesphere.codereview.document.CodeReviewRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CodeReviewRecordRepository extends MongoRepository<CodeReviewRecord, String> {

    /**
     * Every finder below is scoped by {@code organizationId} in addition to {@code
     * microserviceId} — a caller from one organization can never read, sync, merge, close, or
     * comment on another organization's pull request, even if they know or guess its
     * microserviceId. {@code organizationId} always comes from the calling user's own verified
     * token (see {@code AuthenticatedCaller#organizationId()}), never from anything client-supplied.
     */
    List<CodeReviewRecord> findByMicroserviceIdAndOrganizationIdOrderByCreatedAtDesc(
            String microserviceId, String organizationId);

    Optional<CodeReviewRecord> findFirstByMicroserviceIdAndOrganizationIdOrderByCreatedAtDesc(
            String microserviceId, String organizationId);

    Optional<CodeReviewRecord> findFirstByMicroserviceIdAndOrganizationIdAndReviewStatusNotInOrderByCreatedAtDesc(
            String microserviceId, String organizationId, List<String> terminalStatuses);
}
