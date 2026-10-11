package com.example.ragpoc.document.service;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.document.DocumentNotFoundException;
import com.example.ragpoc.document.entity.Document;
import com.example.ragpoc.document.entity.DocumentVersion;
import com.example.ragpoc.document.repository.DocumentVersionRepository;
import com.example.ragpoc.storage.FileStorageService;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the stored file behind a document's active version. */
@Service
public class DocumentContentService {

  private final DocumentVersionRepository versions;
  private final FileStorageService storage;

  public DocumentContentService(DocumentVersionRepository versions, FileStorageService storage) {
    this.versions = versions;
    this.storage = storage;
  }

  /** The active version's file, not the newest: a replacement still ingesting must not change it. */
  @Transactional(readOnly = true)
  public Path activeVersionFile(CidContext cid, Document document) {
    int version = document.getCurrentVersion();
    if (version == 0) {
      throw new DocumentNotFoundException(document.getId());
    }
    DocumentVersion documentVersion =
        versions
            .findByDocumentIdAndVersion(document.getId(), version)
            .orElseThrow(() -> new DocumentNotFoundException(document.getId()));
    return storage.pathOf(cid, documentVersion.getStorageKey());
  }
}
