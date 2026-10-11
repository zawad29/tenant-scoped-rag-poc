package com.example.ragpoc.cid.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A company (cid). A user belongs to exactly one cid.
 *
 * <p>A plain JPA entity rather than a record, so state changes are dirty-checked.
 */
@Entity
@Table(name = "cid")
public class Cid {

  @Id private UUID id;

  @Column(nullable = false)
  private String name;

  private String address;

  private String email;

  @Column(nullable = false)
  private String status = "ACTIVE";

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  protected Cid() {}

  public Cid(UUID id, String name, String address, String email, String status) {
    this.id = id;
    this.name = name;
    this.address = address;
    this.email = email;
    this.status = status == null || status.isBlank() ? "ACTIVE" : status;
  }

  public static Cid create(String name, String address, String email) {
    return new Cid(UUID.randomUUID(), name, address, email, "ACTIVE");
  }

  public void updateProfile(String name, String address, String email) {
    this.name = name;
    this.address = address;
    this.email = email;
  }

  public boolean active() {
    return "ACTIVE".equals(status);
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getAddress() {
    return address;
  }

  public String getEmail() {
    return email;
  }

  public String getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
