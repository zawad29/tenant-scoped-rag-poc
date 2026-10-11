package com.example.ragpoc.document.repository;

import com.example.ragpoc.document.entity.DocumentVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Version persistence. */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

  Optional<DocumentVersion> findByDocumentIdAndVersion(UUID documentId, int version);

  List<DocumentVersion> findByDocumentIdOrderByVersionDesc(UUID documentId);

  /** Versions other than the one given, used to clean up after a replacement. */
  List<DocumentVersion> findByDocumentIdAndVersionNot(UUID documentId, int keepVersion);

  /** Next version number for a document; 1 for a document with no versions yet. */
  @Query("select coalesce(max(v.version), 0) + 1 from DocumentVersion v where v.documentId = :documentId")
  int nextVersion(@Param("documentId") UUID documentId);

  void deleteByDocumentIdAndVersion(UUID documentId, int version);
}
