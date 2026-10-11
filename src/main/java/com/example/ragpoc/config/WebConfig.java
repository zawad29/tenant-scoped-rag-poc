package com.example.ragpoc.config;

import com.example.ragpoc.security.CidContextArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web layer configuration. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final CidContextArgumentResolver cidContextArgumentResolver;

  public WebConfig(CidContextArgumentResolver cidContextArgumentResolver) {
    this.cidContextArgumentResolver = cidContextArgumentResolver;
  }

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    // Lets controllers declare `CidContext cid` as a parameter instead of
    // looking it up, so the cid arrives as an explicit argument.
    resolvers.add(cidContextArgumentResolver);
  }
}
