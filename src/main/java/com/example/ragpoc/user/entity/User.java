package com.example.ragpoc.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A user as stored, including the password hash.
 *
 * <p>Never leaves the persistence and authentication boundary: the web layer sees
 * {@link com.example.ragpoc.security.AppUserPrincipal}, which does not carry the
 * hash. Every user belongs to exactly one cid.
 */
@Entity
@Table(name = "app_user")
public class User {

  @Id private UUID id;

  @Column(name = "cid_id", nullable = false)
  private UUID cidId;

  @Column(nullable = false)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Column(nullable = false)
  private boolean enabled = true;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected User() {}

  public User(
      UUID id, UUID cidId, String email, String passwordHash, Role role, boolean enabled) {
    this.id = id;
    this.cidId = cidId;
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
    this.enabled = enabled;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCidId() {
    return cidId;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Role getRole() {
    return role;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
