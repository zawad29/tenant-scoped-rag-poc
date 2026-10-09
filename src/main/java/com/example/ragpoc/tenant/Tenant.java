package com.example.ragpoc.tenant;

import java.util.UUID;

/** A tenant organisation. */
public record Tenant(UUID id, String name, String status) {

  public boolean active() {
    return "ACTIVE".equals(status);
  }
}
