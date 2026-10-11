package com.example.ragpoc.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Guards the demo migration: a typo in a BCrypt hash would not fail the build,
 * it would just make the documented demo login stop working.
 */
class DemoDataMigrationTest {

  private static final String MIGRATION = "/db/migration/V2__demo_data.sql";
  private static final String PASSWORD = "demo1234";

  @Test
  @DisplayName("every demo password hash verifies against the documented password")
  void demoHashesVerify() throws Exception {
    String sql = read(MIGRATION);

    List<String> hashes =
        Pattern.compile("\\$2a\\$\\d\\d\\$[./A-Za-z0-9]{53}")
            .matcher(sql)
            .results()
            .map(MatchResult::group)
            .toList();

    assertThat(hashes).hasSize(4);

    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    for (String hash : hashes) {
      assertThat(encoder.matches(PASSWORD, hash)).as("hash %s", hash).isTrue();
    }
  }

  @Test
  @DisplayName("the demo data defines two cids, two admins and two regular users")
  void demoDataShape() throws Exception {
    String sql = read(MIGRATION);

    assertThat(sql).contains("BRAC Bank", "City Bank");
    assertThat(count(sql, "'CID_ADMIN'")).isEqualTo(2);
    assertThat(count(sql, "'USER'")).isEqualTo(2);
  }

  private static int count(String text, String needle) {
    return text.split(Pattern.quote(needle), -1).length - 1;
  }

  private static String read(String resource) throws Exception {
    try (InputStream in = DemoDataMigrationTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
