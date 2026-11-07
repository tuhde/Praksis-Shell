// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.shell.core.Message;
import it.uhde.praksis.shell.core.ModelClient;
import it.uhde.praksis.shell.core.Session;
import it.uhde.praksis.shell.core.ShellConfig;
import it.uhde.praksis.shell.core.ToolCall;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersonChannelTest {
  /** What the daemon was asked, and what it answers. */
  private final List<Request> asked = new ArrayList<>();

  private Response.ApprovalList approvals =
      new Response.ApprovalList(List.of(), Map.of(), Map.of());
  private Response.PlanDetail plan;
  private boolean down;

  private PersonChannel.Daemon daemon() {
    return request -> {
      if (down) {
        throw new IOException("no socket");
      }
      asked.add(request);
      return switch (request) {
        case Request.Approvals a -> approvals;
        case Request.Answer a -> new Response.Done("Answered #" + a.id() + ": " + a.decision());
        case Request.AgentPlans p ->
            new Response.PlanList(plan == null ? List.of() : List.of(plan.plan()));
        case Request.AgentPlanShow p ->
            plan != null && plan.plan().id().equals(p.id())
                ? plan
                : new Response.Error("there is no plan " + p.id());
        case Request.AgentPlanRun p -> plan;
        case Request.AgentPlanDrop p -> new Response.Done("Dropped " + p.id());
        default -> new Response.Error("not asked here");
      };
    };
  }

  private static Response.ApprovalList.Item item(long id, String args) {
    return new Response.ApprovalList.Item(
        id, "t", "PENDING", "mail.10-spam", "mail", "mail.move", Map.of("folder", args));
  }

  private static Response.PlanDetail plan(String id, boolean runnable) {
    return new Response.PlanDetail(
        new Response.Plan(
            id,
            "t",
            "archive the invoices",
            "r",
            "mine",
            "PROPOSED",
            List.of(
                new Response.PlanStep(
                    "mail.move",
                    Map.of("message", "m1", "folder", "Archive"),
                    "local-write",
                    true,
                    "",
                    "",
                    "not in your request or in what was read: message = \"m1\""))),
        runnable,
        runnable ? "" : "the plan layer has no grant");
  }

  /** Runs these lines through the channel; returns what was printed. */
  private String run(String input, String... commands) {
    var out = new ByteArrayOutputStream();
    var print = new PrintStream(out, true, StandardCharsets.UTF_8);
    var in = new BufferedReader(new StringReader(input));
    var channel = new PersonChannel(daemon(), "/home/me/.praksis/praksis.sock", in, print);
    for (String c : commands) {
      channel.run(c);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void theApprovalsThatWaitAreListedAndTextFromOutsideIsCleaned() {
    approvals =
        new Response.ApprovalList(
            List.of(
                item(3, "Archive\u001b[2J"),
                new Response.ApprovalList.Item(4, "t", "DENIED", "r", "l", "c", Map.of())),
            Map.of(),
            Map.of());
    String out = run("", "/approvals");
    assertThat(out).contains("#3").contains("mail.10-spam").contains("wants mail.move");
    assertThat(out).doesNotContain("#4").doesNotContain("\u001b");
    assertThat(out).contains("desktop program");
  }

  @Test
  void allowingOnceShowsTheRequestAsksAndSendsOnlyOnce() {
    approvals = new Response.ApprovalList(List.of(item(3, "Archive")), Map.of(), Map.of());
    String out = run("y\n", "/allow 3");
    assertThat(out)
        .contains("wants mail.move")
        .contains("Allow this once? [y/N]")
        .contains("Answered #3: once");
    assertThat(asked).contains(new Request.Answer(3, "once", null));
    // never a grant that lasts
    assertThat(asked)
        .noneMatch(r -> r instanceof Request.Answer a && a.decision().equals("always"));
  }

  @Test
  void anythingButYesDoesNotAllow() {
    approvals = new Response.ApprovalList(List.of(item(3, "Archive")), Map.of(), Map.of());
    for (String answer : new String[] {"n\n", "\n", "maybe\n", ""}) {
      asked.clear();
      String out = run(answer, "/allow 3");
      assertThat(out).contains("Not allowed.");
      assertThat(asked).noneMatch(r -> r instanceof Request.Answer);
    }
  }

  @Test
  void deniedAndUnknownRequests() {
    approvals = new Response.ApprovalList(List.of(item(3, "Archive")), Map.of(), Map.of());
    assertThat(run("", "/deny 3")).contains("Answered #3: deny");
    assertThat(asked).contains(new Request.Answer(3, "deny", null));
    asked.clear();
    assertThat(run("", "/allow 99")).contains("There is no request #99");
    assertThat(run("", "/allow")).contains("Say which request");
    assertThat(run("", "/allow banana")).contains("Say which request");
    assertThat(asked).noneMatch(r -> r instanceof Request.Answer);
  }

  @Test
  void plansAreListedAndShownWithTheirWarnings() {
    plan = plan("p1", true);
    String list = run("", "/plans");
    assertThat(list)
        .contains("p1")
        .contains("PROPOSED")
        .contains("archive the invoices")
        .contains("1 steps");
    String shown = run("", "/plan p1");
    assertThat(shown)
        .contains("mail.move")
        .contains("CHECK: not in your request")
        .contains("It can be run.");
    assertThat(asked).noneMatch(r -> r instanceof Request.AgentPlanRun);
  }

  @Test
  void aPlanRunsOnlyWhenThePersonTypesItsIdAfterReadingIt() {
    plan = plan("p1", true);
    String out = run("p1\n", "/plan-run p1");
    assertThat(out).contains("mail.move").contains("Type the id of the plan");
    assertThat(asked).contains(new Request.AgentPlanRun("p1"));
    for (String typed : new String[] {"y\n", "yes\n", "p2\n", "\n", ""}) {
      asked.clear();
      assertThat(run(typed, "/plan-run p1")).contains("Not run.");
      assertThat(asked).noneMatch(r -> r instanceof Request.AgentPlanRun);
    }
  }

  @Test
  void aPlanThatCannotRunIsNotOffered() {
    plan = plan("p1", false);
    String out = run("p1\n", "/plan-run p1");
    assertThat(out)
        .contains("It cannot be run: the plan layer has no grant")
        .doesNotContain("Type the id");
    assertThat(asked).noneMatch(r -> r instanceof Request.AgentPlanRun);
  }

  @Test
  void aPlanCanBeDropped() {
    assertThat(run("", "/plan-drop p1")).contains("Dropped p1");
    assertThat(run("", "/plan-drop")).contains("Say which plan");
  }

  @Test
  void aDaemonThatIsNotThereIsSaidPlainly() {
    down = true;
    assertThat(run("", "/approvals"))
        .contains("Cannot reach the daemon at /home/me/.praksis/praksis.sock");
    assertThat(run("", "/plan-run p1")).contains("Cannot reach the daemon");
  }

  @Test
  void anErrorOfTheDaemonIsShownInItsWords() {
    plan = null;
    assertThat(run("", "/plan nope")).contains("The daemon says: there is no plan nope");
  }

  @Test
  void onlyTheCommandsOfTheChannelAreItsOwn() {
    for (String c :
        new String[] {
          "/approvals", "/allow 1", "/deny 1", "/plans", "/plan p", "/plan-run p", "/plan-drop p"
        }) {
      assertThat(PersonChannel.handles(c)).as(c).isTrue();
    }
    for (String c :
        new String[] {
          "/yes", "/quit", "/allowall", "/grant 1", "/always 1", "/revoke 1", "hello", "/stop"
        }) {
      assertThat(PersonChannel.handles(c)).as(c).isFalse();
    }
  }

  // ---------------------------------------------------------------- with the conversation

  /** Runs lines through the loop of the terminal with a model and the channel. */
  private String conversation(ReplTest.Model model, String input) throws IOException {
    var out = new ByteArrayOutputStream();
    var print = new PrintStream(out, true, StandardCharsets.UTF_8);
    var in = new BufferedReader(new StringReader(input));
    var bridge = new ReplTest.Bridge();
    var channel = new PersonChannel(daemon(), "sock", in, print);
    var session =
        new Session(model, bridge, new TerminalListener(print, in), ShellConfig.Limits.DEFAULT);
    new Repl(session, bridge, channel, in, print).run();
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void whatTheChannelPrintsIsNeverPartOfTheConversation() throws IOException {
    approvals = new Response.ApprovalList(List.of(item(3, "SECRET-FOLDER")), Map.of(), Map.of());
    plan = plan("p-secret", true);
    var model = new ReplTest.Model();
    String out = conversation(model, "/approvals\n/plans\n/plan p-secret\nhello\n/quit\n");
    assertThat(out).contains("SECRET-FOLDER").contains("p-secret");
    assertThat(model.histories).hasSize(1);
    String everythingTheModelSaw =
        model.histories.get(0).stream().map(Message::content).reduce("", String::concat);
    assertThat(everythingTheModelSaw).isEqualTo("hello");
    assertThat(everythingTheModelSaw)
        .doesNotContain("SECRET-FOLDER")
        .doesNotContain("p-secret")
        .doesNotContain("mail.10-spam");
  }

  @Test
  void aCommandThatTheModelWritesIsOnlyTextAndNeverReachesTheDaemon() throws IOException {
    approvals = new Response.ApprovalList(List.of(item(3, "Archive")), Map.of(), Map.of());
    plan = plan("p1", true);
    var model = new ReplTest.Model();
    model.replies.add(new ModelClient.Reply("/allow 3\n/plan-run p1\n/deny 3", List.of(), 0, 0));
    String out = conversation(model, "say them\ny\np1\n/quit\n");
    assertThat(out).contains("/allow 3");
    // the answers "y" and "p1" were typed lines that went to the model, not to a command
    assertThat(asked).isEmpty();
    assertThat(model.requests).contains("say them", "y", "p1");
  }

  @Test
  void aToolCallOfTheModelWithTheNameOfACommandIsNoCommand() throws IOException {
    var model = new ReplTest.Model();
    model.replies.add(
        new ModelClient.Reply("", List.of(new ToolCall("1", "/allow", Map.of("id", 3))), 0, 0));
    conversation(model, "do it\n/quit\n");
    assertThat(asked).isEmpty();
  }

  @Test
  void theHomeOfTheDaemonIsFoundLikeThePraksisCommandFindsIt() {
    Path user = Path.of("/home/me");
    ShellConfig plain =
        ShellConfig.parse(
            "[profile.l]\napi=\"ollama\"\nurl=\"http://127.0.0.1:1\"\nmodel=\"m\"\ndata=\"local\"\n[mcp]\ncommand=[\"x\"]\n");
    assertThat(DaemonHome.of(plain, n -> null, user)).isEqualTo(Path.of("/home/me/.praksis"));
    assertThat(DaemonHome.of(plain, n -> n.equals("PRAKSIS_HOME") ? "/srv/p" : null, user))
        .isEqualTo(Path.of("/srv/p"));
    assertThat(DaemonHome.of(plain, n -> n.equals("PRAKSIS_IDENTITY") ? "work" : null, user))
        .isEqualTo(Path.of("/home/me/.praksis-work"));
    ShellConfig withHome =
        ShellConfig.parse(
            "[profile.l]\napi=\"ollama\"\nurl=\"http://127.0.0.1:1\"\nmodel=\"m\"\ndata=\"local\"\n[mcp]\ncommand=[\"x\"]\n[daemon]\nhome=\"~/p2\"\n");
    assertThat(DaemonHome.of(withHome, n -> "/ignored", user)).isEqualTo(Path.of("/home/me/p2"));
    ShellConfig withIdentity =
        ShellConfig.parse(
            "[profile.l]\napi=\"ollama\"\nurl=\"http://127.0.0.1:1\"\nmodel=\"m\"\ndata=\"local\"\n[mcp]\ncommand=[\"x\"]\n[daemon]\nidentity=\"private\"\n");
    assertThat(DaemonHome.of(withIdentity, n -> "/ignored", user))
        .isEqualTo(Path.of("/home/me/.praksis-private"));
    assertThat(DaemonHome.socket(Path.of("/h"))).isEqualTo(Path.of("/h/praksis.sock"));
  }
}
