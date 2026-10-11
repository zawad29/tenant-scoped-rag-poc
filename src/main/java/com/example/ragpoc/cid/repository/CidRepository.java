package com.example.ragpoc.cid.repository;

import com.example.ragpoc.cid.entity.Cid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** CID persistence. */
public interface CidRepository extends JpaRepository<Cid, UUID> {

  @Override
  Optional<Cid> findById(UUID id);

  List<Cid> findAllByOrderByNameAsc();
}
