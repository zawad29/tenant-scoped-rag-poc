package com.example.ragpoc.user.repository;

import com.example.ragpoc.user.entity.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** User persistence. */
public interface UserRepository extends JpaRepository<User, UUID> {

  /**
   * Finds a user by email for authentication.
   *
   * <p>The one lookup that is deliberately not cid-scoped: it happens before any
   * cid is known, and is what establishes the cid.
   */
  Optional<User> findByEmailIgnoreCase(String email);

  /** Cid-scoped lookup: the pattern every other repository method follows. */
  Optional<User> findByIdAndCidId(UUID id, UUID cidId);

  List<User> findByCidIdOrderByEmailAsc(UUID cidId);
}
