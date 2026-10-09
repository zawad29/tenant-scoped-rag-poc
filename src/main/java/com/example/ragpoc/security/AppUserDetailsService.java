package com.example.ragpoc.security;

import com.example.ragpoc.tenant.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads users for authentication from the database. */
@Service
public class AppUserDetailsService implements UserDetailsService {

  private final UserRepository users;

  public AppUserDetailsService(UserRepository users) {
    this.users = users;
  }

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    // A single generic message for both "no such user" and "wrong password":
    // distinguishing them would let an attacker enumerate accounts, and account
    // existence is also a hint about which organisations are enrolled.
    return users
        .findByEmail(email)
        .map(AppUserPrincipal::from)
        .orElseThrow(
            () -> new UsernameNotFoundException("Authentication failed for the supplied credentials"));
  }
}
