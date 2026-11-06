// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShellConfigTest {
  static final String GOOD =
      """
      default = "local"

      [profile.local]
      api   = "ollama"
      url   = "http://127.0.0.1:11434"
      model = "llama3.1"
      data  = "local"

      [mcp]
      command = ["java", "-jar", "/opt/praksis-mcp.jar"]
      mode    = "propose"
      """;

  @Test
  void readsAProfileTheMcpServerAndTheDefaults() {
    ShellConfig c = ShellConfig.parse(GOOD);
    assertThat(c.profile().model()).isEqualTo("llama3.1");
    assertThat(c.profile().api()).isEqualTo("ollama");
    assertThat(c.profile().timeoutMillis()).isEqualTo(120_000);
    assertThat(c.mcpCommand()).containsExactly("java", "-jar", "/opt/praksis-mcp.jar");
    assertThat(c.mcpMode()).isEqualTo("propose");
    assertThat(c.mcpUrl()).isEqualTo("http://127.0.0.1:8484");
    assertThat(c.tokenEnv()).isEqualTo("PRAKSIS_TOKEN");
    assertThat(c.limits()).isEqualTo(ShellConfig.Limits.DEFAULT);
  }

  @Test
  void aSingleProfileNeedsNoDefault() {
    assertThat(ShellConfig.parse(GOOD.replace("default = \"local\"\n", "")).profile().name())
        .isEqualTo("local");
  }

  @Test
  void aModelInTheCloudIsRefusedForNow() {
    assertThatThrownBy(
            () -> ShellConfig.parse(GOOD.replace("data  = \"local\"", "data = \"cloud\"")))
        .hasMessageContaining("data must be \"local\"");
    assertThatThrownBy(() -> ShellConfig.parse(GOOD.replace("data  = \"local\"", "")))
        .hasMessageContaining("data must be \"local\"");
  }

  @Test
  void aLocalProfileMustPointAtThisMachine() {
    for (String url :
        new String[] {
          "http://192.168.1.5:11434", "http://example.org:11434", "http://localhost.evil.org"
        }) {
      assertThatThrownBy(() -> ShellConfig.parse(GOOD.replace("http://127.0.0.1:11434", url)))
          .as(url)
          .hasMessageContaining("needs an address of this machine");
    }
    for (String url :
        new String[] {"http://localhost:11434", "http://[::1]:11434", "http://127.0.0.2:1"}) {
      assertThat(ShellConfig.parse(GOOD.replace("http://127.0.0.1:11434", url)).profile().base())
          .as(url)
          .isNotNull();
    }
  }

  @Test
  void checksTheOtherSettings() {
    assertThatThrownBy(() -> ShellConfig.parse(GOOD.replace("\"ollama\"", "\"anthropic\"")))
        .hasMessageContaining("api must be");
    assertThatThrownBy(() -> ShellConfig.parse(GOOD.replace("model = \"llama3.1\"", "")))
        .hasMessageContaining("model is required");
    assertThatThrownBy(
            () -> ShellConfig.parse(GOOD.replace("mode    = \"propose\"", "mode = \"all\"")))
        .hasMessageContaining("mode must be");
    assertThatThrownBy(
            () ->
                ShellConfig.parse(
                    GOOD.replace("command = [\"java\", \"-jar\", \"/opt/praksis-mcp.jar\"]", "")))
        .hasMessageContaining("command is required");
    assertThatThrownBy(
            () -> ShellConfig.parse(GOOD.replace("default = \"local\"", "default = \"other\"")))
        .hasMessageContaining("names no profile");
    assertThatThrownBy(() -> ShellConfig.parse("[mcp]\ncommand = [\"x\"]"))
        .hasMessageContaining("[profile.NAME]");
    assertThatThrownBy(() -> ShellConfig.parse(GOOD + "[session]\nmax_turns = 0\n"))
        .hasMessageContaining("max_turns");
    assertThatThrownBy(() -> ShellConfig.parse("not toml ["))
        .hasMessageContaining("not valid TOML");
  }

  @Test
  void theLimitsCanBeSet() {
    ShellConfig c =
        ShellConfig.parse(
            GOOD + "[session]\nmax_tool_calls = 5\nmax_turns = 3\nconfirm_tools = false\n");
    assertThat(c.limits()).isEqualTo(new ShellConfig.Limits(5, 3, false));
  }
}
