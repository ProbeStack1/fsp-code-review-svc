package com.probestack.forgesphere.codereview.repository;

import com.probestack.forgesphere.codereview.document.CodeReviewRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface CodeReviewRecordRepository extends MongoRepository<CodeReviewRecord, String> {

    List<CodeReviewRecord> findByMicroserviceIdOrderByCreatedAtDesc(String microserviceId);

    Optional<CodeReviewRecord> findFirstByMicroserviceIdOrderByCreatedAtDesc(String microserviceId);

    Optional<CodeReviewRecord> findFirstByMicroserviceIdAndReviewStatusNotInOrderByCreatedAtDesc(
            String microserviceId, List<String> terminalStatuses);
}
