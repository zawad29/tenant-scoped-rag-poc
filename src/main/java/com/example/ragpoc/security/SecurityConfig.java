package com.example.ragpoc.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication and route-level authorization.
 *
 * <p>Session-based form login. CSRF stays on: htmx POSTs send the token, and
 * turning it off would leave every state-changing admin endpoint open.
 *
 * <p>Route rules are a coarse first filter only. The real access decision is
 * always the cid filter inside the query.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/login", "/css/**", "/js/**", "/favicon.ico", "/actuator/health")
                    .permitAll()
                    .requestMatchers("/admin/**")
                    .hasRole("CID_ADMIN")
                    .anyRequest()
                    .authenticated())
        .formLogin(
            form ->
                form.loginPage("/login")
                    .failureUrl("/login?error")
                    .defaultSuccessUrl("/", false)
                    .permitAll())
        .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
        .csrf(Customizer.withDefaults());
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
