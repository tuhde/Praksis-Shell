// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.shell.core.Message;
import it.uhde.praksis.shell.core.ModelClient;
import it.uhde.praksis.shell.core.ModelException;
import it.uhde.praksis.shell.core.Session;
import it.uhde.praksis.shell.core.ShellConfig;
import it.uhde.praksis.shell.core.ToolBridge;
import it.uhde.praksis.shell.core.ToolCall;
import it.uhde.praksis.shell.core.ToolSpec;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReplTest {
  /** Says what the test wrote; remembers what it was asked. */
  static final class Model implements ModelClient {
    final List<ModelClient.Reply> replies = new ArrayList<>();
    final List<String> requests = new ArrayList<>();
    final List<List<Message>> histories = new ArrayList<>();
    List<String> pieces;
    String where = "local";

    @Override
    public Reply chat(String system, List<Message> history, List<ToolSpec> tools)
        throws ModelException {
      requests.add(history.get(history.size() - 1).content());
      histories.add(List.copyOf(history));
      return replies.isEmpty() ? new Reply("fine", List.of(), 0, 0) : replies.remove(0);
    }

    @Override
    public Reply chat(
        String system,
        List<Message> history,
        List<ToolSpec> tools,
        java.util.function.Consumer<String> onText)
        throws ModelException {
      Reply reply = chat(system, history, tools);
      if (pieces != null) {
        pieces.forEach(onText);
      } else if (!reply.text().isEmpty()) {
        onText.accept(reply.text());
      }
      return reply;
    }

    @Override
    public String where() {
      return where;
    }

    @Override
    public String name() {
      return "m";
    }

    @Override
    public String destination() {
      return where.equals("cloud") ? "api.example.com" : "this machine";
    }
  }

  static final class Bridge implements ToolBridge {
    final List<String> called = new ArrayList<>();

    @Override
    public List<ToolSpec> tools() {
      return List.of(
          new ToolSpec("look", "looks", Map.of(), false),
          new ToolSpec("change", "changes", Map.of(), true));
    }

    @Override
    public Result call(String name, Map<String, Object> arguments) {
      called.add(name);
      return new Result("done", false);
    }

    @Override
    public void close() {}
  }

  private final Model model = new Model();
  private final Bridge bridge = new Bridge();

  /** Runs the loop on these lines; returns what was printed. */
  private String run(String input) throws IOException {
    var out = new ByteArrayOutputStream();
    var print = new PrintStream(out, true, StandardCharsets.UTF_8);
    var in = new BufferedReader(new StringReader(input));
    var session =
        new Session(model, bridge, new TerminalListener(print, in), ShellConfig.Limits.DEFAULT);
    new Repl(session, bridge, null, in, print).run();
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void aLineGoesToTheModelAndItsAnswerIsShown() throws IOException {
    model.replies.add(new ModelClient.Reply("All quiet.", List.of(), 0, 0));
    String out = run("how is it?\n/quit\n");
    assertThat(model.requests).containsExactly("how is it?");
    assertThat(out).contains("All quiet.");
  }

  @Test
  void commandsWithASlashAreHandledHereAndNeverSentToTheModel() throws IOException {
    String out = run("/tools\n/help\n/clear\n/nonsense\n/quit\n");
    assertThat(model.requests).isEmpty();
    assertThat(out)
        .contains("look")
        .contains("change  [may change]")
        .contains("/clear")
        .contains("forgotten")
        .contains("Unknown command");
    assertThat(out).contains("for the desktop program or the praksis command");
  }

  @Test
  void aSlashCommandThatTheModelWritesIsOnlyText() throws IOException {
    model.replies.add(new ModelClient.Reply("/quit", List.of(), 0, 0));
    String out = run("say a command\nnext\n/quit\n");
    // the model wrote /quit and the loop went on: the next line still reached the model
    assertThat(model.requests).containsExactly("say a command", "next");
    assertThat(out).contains("/quit");
  }

  @Test
  void aToolThatMayChangeThingsIsAskedAboutAndTheAnswerIsTheNextLineTheUserTypes()
      throws IOException {
    model.replies.add(
        new ModelClient.Reply("", List.of(new ToolCall("1", "change", Map.of("x", 1))), 0, 0));
    model.replies.add(new ModelClient.Reply("Done.", List.of(), 0, 0));
    String out = run("do it\nn\n/quit\n");
    assertThat(out).contains("Allow change {x=1}? [y/N]").contains("declined");
    assertThat(bridge.called).isEmpty();
    // the "n" was the answer, not a request to the model
    assertThat(model.requests).doesNotContain("n");

    model.replies.add(
        new ModelClient.Reply("", List.of(new ToolCall("2", "change", Map.of())), 0, 0));
    model.replies.add(new ModelClient.Reply("Done.", List.of(), 0, 0));
    run("again\ny\n/quit\n");
    assertThat(bridge.called).containsExactly("change");
  }

  @Test
  void anEndOfInputWhileAskingIsNo() throws IOException {
    model.replies.add(
        new ModelClient.Reply("", List.of(new ToolCall("1", "change", Map.of())), 0, 0));
    run("do it\n");
    assertThat(bridge.called).isEmpty();
  }

  @Test
  void whatTheModelWritesCannotChangeTheTerminal() throws IOException {
    model.replies.add(new ModelClient.Reply("hello\u001b[2Jworld", List.of(), 0, 0));
    String out = run("x\n/quit\n");
    assertThat(out).doesNotContain("\u001b").contains("hello�[2Jworld");
  }

  @Test
  void aToolResultIsShownAsALengthOnlyNotItsText() throws IOException {
    model.replies.add(
        new ModelClient.Reply("", List.of(new ToolCall("1", "look", Map.of())), 0, 0));
    String out = run("x\n/quit\n");
    assertThat(out).contains("-> look").contains("<- 4 characters").doesNotContain("done\n");
  }

  @Test
  void aModelInTheCloudGetsNothingUntilThePersonTypesYes() throws IOException {
    model.where = "cloud";
    model.replies.add(new ModelClient.Reply("Hello.", List.of(), 0, 0));
    String out = run("first request\n/yes\nsecond request\n/quit\n");
    assertThat(model.requests).containsExactly("second request");
    assertThat(out)
        .contains("Nothing was sent")
        .contains("api.example.com")
        .contains("Allowed for this session")
        .contains("Hello.");
  }

  @Test
  void yesOnAModelOnThisMachineHasNothingToAllow() throws IOException {
    String out = run("/yes\n/quit\n");
    assertThat(out).contains("nothing to allow");
  }

  @Test
  void aYesThatTheModelWritesIsOnlyText() throws IOException {
    model.where = "cloud";
    model.replies.add(new ModelClient.Reply("/yes", List.of(), 0, 0));
    // the person allows the session, the model answers "/yes", and a new session is not allowed
    String out = run("/yes\nsay it\n/quit\n");
    assertThat(out).contains("/yes");
    assertThat(model.requests).containsExactly("say it");
  }

  @Test
  void streamedTextIsPrintedAsOneBlockAndAUsageLineFollows() throws IOException {
    model.pieces = List.of("Hel", "lo ", "there\u001b[2J");
    model.replies.add(new ModelClient.Reply("Hello there", List.of(), 1500, 40));
    String out = run("hi\n/quit\n");
    assertThat(out).contains("\nHello there\uFFFD[2J\n");
    assertThat(out).doesNotContain("\u001b");
    assertThat(out).contains("[tokens: 1.5k read, 40 written]");
  }

  @Test
  void usageShowsTheTokensTheLimitAndTheToolCalls() throws IOException {
    model.replies.add(
        new ModelClient.Reply("", List.of(new ToolCall("1", "look", Map.of())), 900, 10));
    model.replies.add(new ModelClient.Reply("Done.", List.of(), 1200, 20));
    String out = run("x\n/usage\n/quit\n");
    assertThat(out)
        .contains("Tokens in this session: 2.1k read, 30 written, no limit; 1 tool calls.");
    assertThat(model.requests).containsExactly("x", "done");
  }

  @Test
  void countsAreShortAndReadable() {
    assertThat(Terminal.count(0)).isEqualTo("0");
    assertThat(Terminal.count(999)).isEqualTo("999");
    assertThat(Terminal.count(12_345)).isEqualTo("12.3k");
    assertThat(Terminal.count(2_500_000)).isEqualTo("2.5M");
  }
}
