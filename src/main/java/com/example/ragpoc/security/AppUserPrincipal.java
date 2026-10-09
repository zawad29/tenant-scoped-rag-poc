package com.example.ragpoc.security;

import com.example.ragpoc.tenant.AppUser;
import com.example.ragpoc.tenant.Role;
import com.example.ragpoc.tenant.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal.
 *
 * <p>Carries {@code tenantId}, which is the only source of the tenant for the
 * whole request. It comes from the database row that the credentials were
 * verified against, so it cannot be influenced by anything the client sends.
 *
 * <p>Deliberately does not carry the password hash beyond what
 * {@link UserDetails} requires, to keep it out of logs and templates.
 */
public final class AppUserPrincipal implements UserDetails {

  private final UUID userId;
  private final UUID tenantId;
  private final String email;
  private final String passwordHash;
  private final Role role;
  private final boolean enabled;

  private AppUserPrincipal(
      UUID userId,
      UUID tenantId,
      String email,
      String passwordHash,
      Role role,
      boolean enabled) {
    this.userId = userId;
    this.tenantId = tenantId;
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
    this.enabled = enabled;
  }

  public static AppUserPrincipal from(AppUser user) {
    return new AppUserPrincipal(
        user.id(), user.tenantId(), user.email(), user.passwordHash(), user.role(), user.enabled());
  }

  public UUID userId() {
    return userId;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public Role role() {
    return role;
  }

  /**
   * The conversion that starts every tenant-scoped operation.
   *
   * <p>Called once per request at the web boundary and then passed explicitly
   * down the call stack. Nothing about the tenant is ever read again from the
   * request.
   */
  public TenantContext toTenantContext() {
    return TenantContext.of(tenantId, userId, role);
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority(role.authority()));
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public String toString() {
    return "AppUserPrincipal[user=" + userId + ", tenant=" + tenantId + ", role=" + role + "]";
  }
}
