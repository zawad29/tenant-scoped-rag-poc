package com.example.ragpoc.config;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.MultipartFilter;

/**
 * Parses multipart requests before Spring Security runs.
 *
 * <p>Without this, CSRF protection and multipart uploads do not work together:
 * {@code CsrfFilter} reads the token with {@code request.getParameter(...)}, which
 * for {@code multipart/form-data} returns nothing until the body has been parsed,
 * and Spring Security runs long before the DispatcherServlet does that.
 *
 * <p>{@code spring.servlet.multipart.resolve-lazily=true} is required alongside
 * this, so Spring MVC reuses the parsed request instead of re-reading a consumed
 * stream.
 */
@Configuration
public class MultipartConfig {

  @Bean
  public FilterRegistrationBean<MultipartFilter> multipartFilter() {
    FilterRegistrationBean<MultipartFilter> registration =
        new FilterRegistrationBean<>(new MultipartFilter());
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
    registration.addUrlPatterns("/*");
    return registration;
  }
}
