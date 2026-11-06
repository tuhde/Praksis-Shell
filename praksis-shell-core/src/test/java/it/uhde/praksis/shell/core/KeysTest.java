// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeysTest {
  private static Profile env(String variable) {
    return Profile.of(
        "c", "anthropic", null, "claude-x", "cloud", null, null, null, variable, null);
  }

  private static Profile command(String... command) {
    return Profile.of(
        "c", "anthropic", null, "claude-x", "cloud", null, null, null, null, List.of(command));
  }

  @Test
  void aKeyComesFromTheEnvironmentVariableTheProfileNames() {
    Map<String, String> e = Map.of("MY_KEY", "  sk-ant-secret \n");
    assertThat(Keys.resolve(env("MY_KEY"), e::get)).isEqualTo("sk-ant-secret");
    assertThatThrownBy(() -> Keys.resolve(env("OTHER"), e::get))
        .hasMessageContaining("OTHER")
        .hasMessageContaining("is not set")
        .message()
        .doesNotContain("sk-ant-secret");
    assertThatThrownBy(() -> Keys.resolve(env("MY_KEY"), n -> " "))
        .hasMessageContaining("is not set");
  }

  @Test
  void aKeyComesFromTheFirstLineThatACommandPrints() {
    assertThat(
            Keys.resolve(command("sh", "-c", "printf 'sk-from-command\\nignored\\n'"), n -> null))
        .isEqualTo("sk-from-command");
  }

  @Test
  void aFailingCommandIsAnErrorThatDoesNotShowWhatItPrinted() {
    assertThatThrownBy(
            () -> Keys.resolve(command("sh", "-c", "echo SECRET-VALUE; exit 3"), n -> null))
        .hasMessageContaining("key_command failed (exit 3)")
        .message()
        .doesNotContain("SECRET-VALUE");
    assertThatThrownBy(() -> Keys.resolve(command("sh", "-c", "echo oops >&2; exit 0"), n -> null))
        .hasMessageContaining("printed nothing");
    assertThatThrownBy(() -> Keys.resolve(command("/nonexistent/key-tool"), n -> null))
        .hasMessageContaining("cannot run key_command");
  }

  @Test
  void aCommandThatHangsIsStopped() {
    assertThatThrownBy(() -> Keys.resolve(command("sh", "-c", "sleep 30"), n -> null, 1))
        .hasMessageContaining("did not finish in 1 seconds");
  }

  @Test
  void theTextOfAProfileDoesNotNameTheKeySource() {
    assertThat(command("kwallet-query", "-r", "anthropic-entry").toString())
        .doesNotContain("kwallet")
        .doesNotContain("anthropic-entry");
    assertThat(env("MY_SECRET_VAR").toString()).doesNotContain("MY_SECRET_VAR");
  }
}
