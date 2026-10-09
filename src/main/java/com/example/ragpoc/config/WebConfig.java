package com.example.ragpoc.config;

import com.example.ragpoc.security.TenantContextArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web layer configuration. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final TenantContextArgumentResolver tenantContextArgumentResolver;

  public WebConfig(TenantContextArgumentResolver tenantContextArgumentResolver) {
    this.tenantContextArgumentResolver = tenantContextArgumentResolver;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    // Lets controllers declare `TenantContext tenant` as a parameter instead of
    // looking it up, so the tenant arrives as an explicit argument.
    resolvers.add(tenantContextArgumentResolver);
  }
}
