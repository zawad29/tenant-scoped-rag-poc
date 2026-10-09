package com.example.ragpoc.tenant;

import java.util.UUID;

/**
 * A user as stored, including the password hash.
 *
 * <p>Never leaves the persistence and authentication boundary: the web layer
 * and templates see {@link com.example.ragpoc.security.AppUserPrincipal}, which
 * does not carry the hash.
 */
public record AppUser(
    UUID id, UUID tenantId, String email, String passwordHash, Role role, boolean enabled) {}
