package me.apet97.breakcompliance.persistence.repositories;

import java.util.List;
import me.apet97.breakcompliance.persistence.entities.FindingReview;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FindingReviewRepository extends JpaRepository<FindingReview, FindingReview.Pk> {

    List<FindingReview> findByWorkspaceId(String workspaceId);
}
