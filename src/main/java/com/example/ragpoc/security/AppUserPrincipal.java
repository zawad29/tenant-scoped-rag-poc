package com.example.ragpoc.security;

import com.example.ragpoc.cid.CidContext;
import com.example.ragpoc.user.entity.Role;
import com.example.ragpoc.user.entity.User;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal. Carries {@code cidId}, the only source of the cid
 * for the whole request. It comes from the database row the credentials were
 * verified against, so nothing the client sends can influence it.
 */
public final class AppUserPrincipal implements UserDetails {

  private final UUID userId;
  private final UUID cidId;
  private final String email;
  private final String passwordHash;
  private final Role role;
  private final boolean enabled;

  private AppUserPrincipal(
      UUID userId, UUID cidId, String email, String passwordHash, Role role, boolean enabled) {
    this.userId = userId;
    this.cidId = cidId;
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
    this.enabled = enabled;
  }

  public static AppUserPrincipal from(User user) {
    return new AppUserPrincipal(
        user.getId(), user.getCidId(), user.getEmail(), user.getPasswordHash(), user.getRole(), user.isEnabled());
  }

  public UUID userId() {
    return userId;
  }

  public UUID cidId() {
    return cidId;
  }

  public Role role() {
    return role;
  }

  /** The conversion that starts every cid-scoped operation. */
  public CidContext toCidContext() {
    return CidContext.of(cidId, userId, role);
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
    return "AppUserPrincipal[user=" + userId + ", cid=" + cidId + ", role=" + role + "]";
  }
}
