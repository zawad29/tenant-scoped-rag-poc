package com.example.ragpoc.security;

import com.example.ragpoc.cid.CidContext;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies {@link CidContext} to controller methods as an ordinary parameter.
 *
 * <p>This is the single place where the authenticated principal becomes a cid.
 * There is no default: a request without a principal fails rather than
 * proceeding with an anonymous context.
 */
@Component
public class CidContextArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return CidContext.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
      throw new IllegalStateException(
          "No authenticated cid principal is available for this request");
    }
    return principal.toCidContext();
  }
}
