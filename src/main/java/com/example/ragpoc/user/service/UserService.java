package com.example.ragpoc.user.service;

import com.example.ragpoc.user.entity.Role;
import com.example.ragpoc.user.entity.User;
import com.example.ragpoc.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and writes users. */
@Service
public class UserService {

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;

  public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional(readOnly = true)
  public Optional<User> findByEmail(String email) {
    return users.findByEmailIgnoreCase(email);
  }

  @Transactional(readOnly = true)
  public List<User> list(UUID cidId) {
    return users.findByCidIdOrderByEmailAsc(cidId);
  }

  @Transactional
  public User create(UUID cidId, String email, String rawPassword, Role role) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("Email is required");
    }
    if (rawPassword == null || rawPassword.isBlank()) {
      throw new IllegalArgumentException("Password is required");
    }
    User user =
        new User(
            UUID.randomUUID(),
            cidId,
            email.trim(),
            passwordEncoder.encode(rawPassword),
            role,
            true);
    return users.save(user);
  }
}
