package com.example.ragpoc.user.entity;

/** Roles recognized by the application. Every user belongs to exactly one cid. */
public enum Role {
  /** Uploads, replaces and deletes documents, inside their own cid only. */
  CID_ADMIN,

  /** Chats with their own cid's documents. */
  USER;

  public String authority() {
    return "ROLE_" + name();
  }
}
