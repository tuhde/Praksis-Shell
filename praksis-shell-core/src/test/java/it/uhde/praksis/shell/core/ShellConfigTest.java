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
    assertThatThrownBy(() -> ShellConfig.parse(GOOD.replace("\"ollama\"", "\"cohere\"")))
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

  static final String CLOUD =
      """
      default = "claude"

      [profile.claude]
      api       = "anthropic"
      model     = "claude-sonnet-5-5"
      data      = "cloud"
      key_env   = "ANTHROPIC_API_KEY"

      [mcp]
      command = ["java", "-jar", "/opt/praksis-mcp.jar"]
      """;

  @Test
  void aModelInTheCloudNeedsDataCloudAnHttpsAddressAndExactlyOneKeySource() {
    ShellConfig c = ShellConfig.parse(CLOUD);
    assertThat(c.profile().cloud()).isTrue();
    assertThat(c.profile().base().toString()).isEqualTo("https://api.anthropic.com");
    assertThat(c.profile().keyEnv()).isEqualTo("ANTHROPIC_API_KEY");
    // a budget of tokens comes with a model in the cloud
    assertThat(c.limits().maxTokens()).isEqualTo(ShellConfig.CLOUD_TOKEN_LIMIT);
    assertThat(
            ShellConfig.parse(CLOUD + "[session]\nmax_tokens_per_session = 5000\n")
                .limits()
                .maxTokens())
        .isEqualTo(5000);

    assertThatThrownBy(() -> ShellConfig.parse(CLOUD.replace("\"cloud\"", "\"local\"")))
        .hasMessageContaining("needs data = \"cloud\"");
    assertThatThrownBy(
            () ->
                ShellConfig.parse(
                    CLOUD.replace("model     =", "url = \"http://api.example.com\"\nmodel     =")))
        .hasMessageContaining("https://");
    assertThatThrownBy(
            () -> ShellConfig.parse(CLOUD.replace("key_env   = \"ANTHROPIC_API_KEY\"", "")))
        .hasMessageContaining("exactly one of key_env");
    assertThatThrownBy(
            () ->
                ShellConfig.parse(
                    CLOUD.replace(
                        "key_env   = \"ANTHROPIC_API_KEY\"",
                        "key_env = \"A\"\nkey_command = [\"x\"]")))
        .hasMessageContaining("exactly one of key_env");
    assertThatThrownBy(
            () -> ShellConfig.parse(CLOUD.replace("data      =", "temperature = 0.2\ndata      =")))
        .hasMessageContaining("no longer accepts temperature");
  }

  @Test
  void aKeyCommandIsAList() {
    ShellConfig c =
        ShellConfig.parse(
            CLOUD.replace(
                "key_env   = \"ANTHROPIC_API_KEY\"",
                "key_command = [\"kwallet-query\", \"-r\", \"k\"]"));
    assertThat(c.profile().keyCommand()).containsExactly("kwallet-query", "-r", "k");
    assertThat(c.profile().keyEnv()).isNull();
  }

  @Test
  void aKeySourceBelongsToAModelInTheCloudOnly() {
    assertThatThrownBy(
            () ->
                ShellConfig.parse(
                    GOOD.replace("data  = \"local\"", "data = \"local\"\nkey_env = \"X\"")))
        .hasMessageContaining("key_env and key_command are for api = \"anthropic\"");
  }

  @Test
  void aLocalModelHasNoTokenLimitUnlessOneIsSet() {
    assertThat(ShellConfig.parse(GOOD).limits().maxTokens()).isZero();
  }
}
