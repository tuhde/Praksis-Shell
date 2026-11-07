// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.shell.api.Conversation;
import it.uhde.praksis.shell.api.ConversationListener;
import it.uhde.praksis.shell.api.EngineException;
import it.uhde.praksis.shell.api.EngineProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EngineTest {
  private static final String CALL_THEN_TEXT =
      """
      {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"look","arguments":{"a":1}}}]},"done":false}
      {"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":10,"eval_count":2}
      """;

  /** What the program would be shown. */
  private static final class Shown implements ConversationListener {
    final List<String> lines = new ArrayList<>();
    boolean allow = true;

    @Override
    public void textDelta(String piece) {
      lines.add("delta:" + piece);
    }

    @Override
    public void textEnd() {
      lines.add("end");
    }

    @Override
    public void toolCall(String name, String arguments, boolean writes) {
      lines.add("call:" + name + (writes ? "!" : "") + " " + arguments);
    }

    @Override
    public void toolResult(String name, int length, boolean error, String shortText) {
      lines.add("result:" + name + ":" + length + (error ? ":error:" + shortText : ""));
    }

    @Override
    public void note(String text) {
      lines.add("note:" + text);
    }

    @Override
    public void usage(long read, long written, long limit) {
      lines.add("usage:" + read + "/" + written + "/" + limit);
    }

    @Override
    public boolean confirm(String name, String arguments) {
      lines.add("confirm:" + name);
      return allow;
    }
  }

  private static Path config(FakeServer model) throws Exception {
    Path file = Files.createTempFile("shell", ".toml");
    String command =
        List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                TestMcpServer.class.getName())
            .stream()
            .map(s -> "\"" + s.replace("\\", "\\\\") + "\"")
            .toList()
            .toString();
    Files.writeString(
        file,
        "[profile.l]\napi=\"ollama\"\nurl=\""
            + model.base()
            + "\"\nmodel=\"m\"\ndata=\"local\"\n[mcp]\ncommand="
            + command
            + "\nmode=\"propose\"\n");
    return file;
  }

  private static final Function<String, String> ENV =
      n -> n.equals("PRAKSIS_TOKEN") ? "pwt_x" : null;

  @Test
  void theProviderIsRegisteredAsAService() {
    assertThat(ServiceLoader.load(EngineProvider.class).findFirst())
        .containsInstanceOf(Engine.class);
  }

  @Test
  void aConversationRunsAToolAndTellsTheProgramWhatHappenedAndWhatItCost() throws Exception {
    try (var server = new FakeServer(200, CALL_THEN_TEXT, "application/x-ndjson")) {
      var shown = new Shown();
      try (Conversation c = new Engine().open(config(server), ENV, shown)) {
        assertThat(c.info().model()).isEqualTo("m");
        assertThat(c.info().where()).isEqualTo("local");
        assertThat(c.info().destination()).isEqualTo("this machine");
        assertThat(c.info().tools()).isEqualTo(3);
        assertThat(c.info().mode()).isEqualTo("propose");
        assertThat(c.cloudAllowed()).isTrue();
        c.ask("look at it");
      }
      // the scripted model calls the tool every time it is asked, so the turns run out: the program
      // is told about the calls, the results, the limit and the tokens
      assertThat(shown.lines).contains("call:look {a=1}", "result:look:10");
      assertThat(shown.lines).anyMatch(l -> l.startsWith("note:stopped after 12 answers"));
      assertThat(shown.lines).anyMatch(l -> l.matches("usage:120/24/0"));
    }
  }

  @Test
  void aToolThatMayChangeThingsAsksTheProgramAndTheAnswerDecides() throws Exception {
    String callsChange =
        """
        {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"change","arguments":{}}}]},"done":false}
        {"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":1,"eval_count":1}
        """;
    try (var server = new FakeServer(200, callsChange, "application/x-ndjson")) {
      var shown = new Shown();
      shown.allow = false;
      try (Conversation c = new Engine().open(config(server), ENV, shown)) {
        c.ask("change it");
      }
      assertThat(shown.lines).contains("confirm:change", "note:declined: change");
      assertThat(shown.lines).noneMatch(l -> l.startsWith("result:change"));
    }
  }

  @Test
  void aMissingTokenOrConfigurationIsAnEngineExceptionWithTheReason() throws Exception {
    try (var server = new FakeServer(200, CALL_THEN_TEXT, "application/x-ndjson")) {
      Path file = config(server);
      assertThatThrownBy(() -> new Engine().open(file, n -> null, new Shown()))
          .isInstanceOf(EngineException.class)
          .hasMessageContaining("PRAKSIS_TOKEN");
      assertThatThrownBy(
              () -> new Engine().open(Path.of("/nonexistent/shell.toml"), ENV, new Shown()))
          .isInstanceOf(EngineException.class)
          .hasMessageContaining("there is no configuration");
    }
  }

  @Test
  void clearForgetsAndCloseStopsTheServer() throws Exception {
    try (var server = new FakeServer(200, CALL_THEN_TEXT, "application/x-ndjson")) {
      Conversation c = new Engine().open(config(server), ENV, new Shown());
      c.ask("x");
      c.clear();
      c.close();
    }
  }
}
