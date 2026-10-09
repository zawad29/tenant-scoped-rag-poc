package com.example.ragpoc.security;

import com.example.ragpoc.tenant.TenantContext;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies {@link TenantContext} to controller methods as an ordinary parameter.
 *
 * <pre>{@code
 * @PostMapping("/admin/documents")
 * String upload(TenantContext tenant, @RequestParam MultipartFile file) { ... }
 * }</pre>
 *
 * <p>This is the single place where the authenticated principal is converted
 * into a tenant, and it happens once per request. Controllers then hand the
 * value to services explicitly.
 *
 * <p>The alternative, reading the tenant from inside a service via
 * {@code SecurityContextHolder} or a {@code ThreadLocal}, is what makes tenant
 * bleed possible: any code path that runs off the request thread — an async
 * ingestion job, a scheduled task, a reactive callback — silently sees the
 * wrong tenant or none at all. Passing the value makes that a compile error
 * instead of an incident.
 *
 * <p>There is no default: a request without an authenticated principal fails
 * rather than proceeding with an anonymous "no tenant" context.
 */
@Component
public class TenantContextArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return TenantContext.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
      // Reachable only if a route is left unauthenticated by mistake; failing
      // closed is the whole point.
      throw new IllegalStateException(
          "No authenticated tenant principal is available for this request");
    }
    return principal.toTenantContext();
  }

  /** Resolves the tenant from an authenticated principal, for non-web callers. */
  public static TenantContext fromAuthentication(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
      throw new IllegalStateException("Authentication does not carry a tenant principal");
    }
    return principal.toTenantContext();
  }
}
