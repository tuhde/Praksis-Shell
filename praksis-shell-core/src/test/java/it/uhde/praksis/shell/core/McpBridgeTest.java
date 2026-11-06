// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bridge against a real MCP server in its own process. */
class McpBridgeTest {
  private static final List<String> COMMAND =
      List.of(
          Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp",
          System.getProperty("java.class.path"),
          TestMcpServer.class.getName());

  private static ShellConfig config() {
    return ShellConfig.parse(
        ShellConfigTest.GOOD.replace(
                "[\"java\", \"-jar\", \"/opt/praksis-mcp.jar\"]",
                COMMAND.stream()
                    .map(s -> "\"" + s.replace("\\", "\\\\") + "\"")
                    .toList()
                    .toString())
            + "\nurl = \"http://127.0.0.1:9\"\n");
  }

  @Test
  void listsTheToolsAndMarksTheOnesThatMayChangeSomething() {
    try (McpBridge bridge = McpBridge.start(COMMAND, Map.of())) {
      assertThat(bridge.tools())
          .extracting(ToolSpec::name)
          .containsExactlyInAnyOrder("look", "change", "env");
      assertThat(
              bridge.tools().stream()
                  .filter(t -> t.name().equals("look"))
                  .findFirst()
                  .orElseThrow()
                  .writes())
          .isFalse();
      assertThat(
              bridge.tools().stream()
                  .filter(t -> t.name().equals("change"))
                  .findFirst()
                  .orElseThrow()
                  .writes())
          .isTrue();
      assertThat(bridge.tools().get(0).schema()).containsEntry("type", "object");
    }
  }

  @Test
  void callsAToolAndReturnsErrorsAsResultsNotExceptions() {
    try (McpBridge bridge = McpBridge.start(COMMAND, Map.of())) {
      assertThat(bridge.call("look", Map.of("a", 1)))
          .isEqualTo(new ToolBridge.Result("seen {a=1}", false));
      assertThat(bridge.call("change", Map.of()))
          .isEqualTo(new ToolBridge.Result("ERR this token is read only", true));
      ToolBridge.Result unknown = bridge.call("praksis_approve", Map.of());
      assertThat(unknown.error()).isTrue();
      assertThat(unknown.text()).contains("there is no tool");
    }
  }

  @Test
  void theTokenComesFromTheEnvironmentAndGoesToTheServerOnly() {
    ShellConfig c = config();
    try (McpBridge bridge =
        McpBridge.start(c, name -> name.equals("PRAKSIS_TOKEN") ? " pwt_x " : null)) {
      assertThat(bridge.call("env", Map.of()).text())
          .isEqualTo("propose,http://127.0.0.1:9,token:pwt_x");
    }
    assertThatThrownBy(() -> McpBridge.start(c, name -> null))
        .hasMessageContaining("PRAKSIS_TOKEN")
        .hasMessageContaining("is not set");
    // the configuration holds the name of the variable and never the token
    assertThat(c.toString()).doesNotContain("pwt_");
  }

  @Test
  void aServerThatCannotStartIsAnErrorThatNamesTheCommand() {
    assertThatThrownBy(() -> McpBridge.start(List.of("/nonexistent/praksis-mcp"), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot start the MCP server of Praksis")
        .hasMessageContaining("/nonexistent/praksis-mcp")
        .hasMessageContaining("not an executable");
    assertThatThrownBy(() -> McpBridge.start(List.of("no-such-program-praksis"), Map.of()))
        .hasMessageContaining("on the PATH");
  }
}
