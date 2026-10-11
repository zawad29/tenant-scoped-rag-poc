package com.example.ragpoc.document.repository;

import com.example.ragpoc.document.entity.Document;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Document persistence. */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

  /** The cid predicate is the access check, not an optimization. */
  Optional<Document> findByIdAndCidId(UUID id, UUID cidId);

  List<Document> findByCidIdOrderByCreatedAtDesc(UUID cidId);

  List<Document> findByCidIdAndCurrentVersionGreaterThan(UUID cidId, int version);
}
