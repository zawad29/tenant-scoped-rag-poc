package com.example.ragpoc.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ragpoc.tenant.TenantContext;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the property that makes tenant isolation structural rather than
 * conventional: it must be impossible to call the vector index without stating
 * which tenant you are acting for.
 *
 * <p>A future contributor adding a convenience method such as
 * {@code denseSearch(queryVector, limit)} would reopen the exact hole the whole
 * design exists to close. This test turns that into a red build.
 */
class VectorIndexPortTenantContractTest {

  private static final List<Class<?>> TENANT_AWARE_PORTS =
      List.of(VectorIndexPort.class);

  @Test
  @DisplayName("every VectorIndexPort method takes a TenantContext as its first parameter")
  void everyMethodRequiresATenantContext() {
    for (Class<?> port : TENANT_AWARE_PORTS) {
      for (Method method : port.getDeclaredMethods()) {
        if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
          continue;
        }
        Class<?>[] parameters = method.getParameterTypes();
        assertThat(parameters)
            .as(
                "%s.%s must accept a TenantContext so that no tenant-free access path exists",
                port.getSimpleName(), method.getName())
            .isNotEmpty();
        assertThat(parameters[0])
            .as(
                "the first parameter of %s.%s must be TenantContext, not %s",
                port.getSimpleName(), method.getName(), parameters[0].getSimpleName())
            .isEqualTo(TenantContext.class);
      }
    }
  }

  @Test
  @DisplayName("VectorIndexPort declares no overload that omits the tenant")
  void noOverloadOmitsTheTenant() {
    String[] methodNames =
        Arrays.stream(VectorIndexPort.class.getDeclaredMethods())
            .filter(m -> !m.isSynthetic() && !Modifier.isStatic(m.getModifiers()))
            .map(Method::getName)
            .distinct()
            .toArray(String[]::new);

    assertThat(methodNames).isNotEmpty();

    for (String name : methodNames) {
      long withoutTenant =
          Arrays.stream(VectorIndexPort.class.getDeclaredMethods())
              .filter(m -> m.getName().equals(name))
              .filter(
                  m ->
                      m.getParameterCount() == 0
                          || !m.getParameterTypes()[0].equals(TenantContext.class))
              .count();

      assertThat(withoutTenant)
          .as(
              "VectorIndexPort.%s has an overload without a leading TenantContext; "
                  + "callers could then forget the tenant filter",
              name)
          .isZero();
    }
  }

  @Test
  @DisplayName("ChunkRecord carries no tenant field, so content cannot be indexed under another tenant")
  void chunkRecordHasNoTenantField() {
    assertThat(Arrays.stream(ChunkRecord.class.getRecordComponents()).map(c -> c.getName()))
        .as("the tenant is supplied by TenantContext, never by the payload")
        .doesNotContain("tenantId", "tenant");
  }
}
