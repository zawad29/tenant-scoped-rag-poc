package com.example.ragpoc.security;

import com.example.ragpoc.user.service.UserService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads users for authentication from the database. */
@Service
public class AppUserDetailsService implements UserDetailsService {

  private final UserService users;

  public AppUserDetailsService(UserService users) {
    this.users = users;
  }

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    // One generic message for both "no such user" and "wrong password": account
    // existence is itself a hint about which companies are enrolled.
    return users
        .findByEmail(email)
        .map(AppUserPrincipal::from)
        .orElseThrow(
            () -> new UsernameNotFoundException("Authentication failed for the supplied credentials"));
  }
}
