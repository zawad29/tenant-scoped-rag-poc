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
 * <p>Session-based form login, appropriate for a server-rendered demo. CSRF
 * protection stays on: htmx POSTs send the token, and turning it off would
 * leave every state-changing admin endpoint open to cross-site requests.
 *
 * <p>Route rules are a coarse first filter only. The real access decision is
 * always the tenant filter inside the query, because authorization here says
 * "this user may upload documents" and never "this user may touch this
 * document" — that depends on the document's tenant, which only the query knows.
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
                    // Document administration is tenant-admin only. Note this
                    // grants the *capability*, not access to any particular
                    // document: the tenant filter decides that.
                    .requestMatchers("/admin/**")
                    .hasRole("TENANT_ADMIN")
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
    // BCrypt at the default strength: deliberately slow, which is what makes
    // stored hashes expensive to attack.
    return new BCryptPasswordEncoder();
  }
}
