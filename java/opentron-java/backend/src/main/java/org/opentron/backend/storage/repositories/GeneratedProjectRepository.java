package org.opentron.backend.storage.repositories;

import org.opentron.backend.storage.entities.GeneratedProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface GeneratedProjectRepository extends JpaRepository<GeneratedProject, Long> {
    Optional<GeneratedProject> findByProjectId(String projectId);
    List<GeneratedProject> findAllByOrderByCreatedAtDesc();
    
    List<GeneratedProject> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
