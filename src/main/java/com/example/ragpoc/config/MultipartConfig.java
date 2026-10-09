package com.example.ragpoc.config;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.support.MultipartFilter;

/**
 * Parses multipart requests before Spring Security runs.
 *
 * <p>Without this, CSRF protection and multipart uploads do not work together.
 * {@code CsrfFilter} reads the token with {@code request.getParameter(...)},
 * which for a {@code multipart/form-data} request returns nothing until the body
 * has been parsed into parameters — and Spring Security's filter runs long
 * before the DispatcherServlet does that. The result is a 403 on every upload,
 * with no hint as to why.
 *
 * <p>The alternatives were worse. Putting the token in the query string keeps
 * secrets out of request bodies but writes them into access logs, and using
 * htmx to send the token as a header would make the form unusable without
 * JavaScript. Registering {@code MultipartFilter} one step ahead of the security
 * chain fixes the ordering problem at its source and leaves the form as ordinary
 * HTML with a hidden field.
 *
 * <p>{@code spring.servlet.multipart.resolve-lazily=true} (see application.yml)
 * is required alongside this: it tells Spring MVC to reuse the multipart request
 * the filter already produced rather than re-parsing the consumed stream.
 *
 * <p>This is exactly the kind of defect that a test using the framework's
 * {@code csrf()} request post-processor hides, because that helper injects a
 * valid token directly. {@code DocumentAdminIT} deliberately scrapes the token
 * out of the rendered page instead, so the real path is covered.
 */
@Configuration
public class MultipartConfig {

  @Bean
  public FilterRegistrationBean<MultipartFilter> multipartFilter() {
    FilterRegistrationBean<MultipartFilter> registration =
        new FilterRegistrationBean<>(new MultipartFilter());
    // SecurityFilterProperties.DEFAULT_FILTER_ORDER is -100; one step earlier
    // means the request is already a MultipartHttpServletRequest by the time
    // CsrfFilter looks for the token. (In Boot 3 this constant lived in
    // org.springframework.boot.autoconfigure.security.SecurityProperties.)
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1);
    registration.addUrlPatterns("/*");
    return registration;
  }
}
