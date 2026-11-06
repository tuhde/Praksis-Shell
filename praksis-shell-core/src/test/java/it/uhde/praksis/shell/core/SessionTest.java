// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionTest {
  /** A model that says what the test wrote, one reply for each time it is asked. */
  static final class Scripted implements ModelClient {
    final List<Object> script;
    final List<List<Message>> asked = new ArrayList<>();
    String where = "local";
    int next;

    Scripted(Object... script) {
      this.script = List.of(script);
    }

    @Override
    public Reply chat(String system, List<Message> history, List<ToolSpec> tools)
        throws ModelException {
      asked.add(List.copyOf(history));
      Object o = script.get(Math.min(next++, script.size() - 1));
      if (o instanceof ModelException e) {
        throw e;
      }
      return (Reply) o;
    }

    @Override
    public String where() {
      return where;
    }

    @Override
    public String name() {
      return "scripted";
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
          new ToolSpec("look", "looks", Map.of("type", "object"), false),
          new ToolSpec("change", "changes", Map.of("type", "object"), true));
    }

    @Override
    public Result call(String name, Map<String, Object> arguments) {
      called.add(name);
      return name.equals("look")
          ? new Result("seen", false)
          : new Result("refused by daemon", true);
    }

    @Override
    public void close() {}
  }

  static final class Shown implements Listener {
    final List<String> lines = new ArrayList<>();
    boolean allow = true;
    int asked;

    @Override
    public void text(String text) {
      lines.add("text:" + text);
    }

    @Override
    public void toolCall(ToolCall call, boolean writes) {
      lines.add("call:" + call.name() + (writes ? "!" : ""));
    }

    @Override
    public void toolResult(ToolCall call, ToolBridge.Result result) {
      lines.add("result:" + result.text());
    }

    @Override
    public void note(String text) {
      lines.add("note:" + text);
    }

    @Override
    public boolean confirm(ToolCall call) {
      asked++;
      return allow;
    }
  }

  static ModelClient.Reply says(String text) {
    return new ModelClient.Reply(text, List.of(), 10, 2);
  }

  static ModelClient.Reply calls(String... names) {
    List<ToolCall> calls = new ArrayList<>();
    for (String n : names) {
      calls.add(new ToolCall("id-" + calls.size(), n, Map.of()));
    }
    return new ModelClient.Reply("", calls, 10, 2);
  }

  private final Bridge bridge = new Bridge();
  private final Shown shown = new Shown();

  private Session session(ModelClient model, ShellConfig.Limits limits) {
    return new Session(model, bridge, shown, limits);
  }

  @Test
  void theModelCallsAToolSeesTheResultAndAnswers() {
    var model = new Scripted(calls("look"), says("All fine."));
    var s = session(model, ShellConfig.Limits.DEFAULT);
    s.ask("how is it?");
    assertThat(bridge.called).containsExactly("look");
    assertThat(shown.lines).containsExactly("call:look", "result:seen", "text:All fine.");
    // the second question to the model has the result of the tool in it
    Message last = model.asked.get(1).get(model.asked.get(1).size() - 1);
    assertThat(last.role()).isEqualTo("tool");
    assertThat(last.content()).isEqualTo("seen");
    assertThat(last.toolCallId()).isEqualTo("id-0");
    assertThat(s.tokensIn()).isEqualTo(20);
    assertThat(s.tokensOut()).isEqualTo(4);
    assertThat(s.toolCalls()).isEqualTo(1);
  }

  @Test
  void aToolThatMayChangeThingsAsksThePersonAndTheModelDoesNotSeeTheQuestion() {
    shown.allow = false;
    var model = new Scripted(calls("change"), says("Understood."));
    session(model, ShellConfig.Limits.DEFAULT).ask("do it");
    assertThat(shown.asked).isEqualTo(1);
    assertThat(bridge.called).isEmpty();
    assertThat(shown.lines).contains("call:change!", "note:declined: change");
    assertThat(model.asked.get(1).get(model.asked.get(1).size() - 1).content())
        .isEqualTo("the person declined this call");
  }

  @Test
  void whenThePersonAllowsItTheCallIsMadeAndADaemonRefusalGoesBackAsAnError() {
    var model = new Scripted(calls("change"), says("The daemon refused."));
    session(model, ShellConfig.Limits.DEFAULT).ask("do it");
    assertThat(bridge.called).containsExactly("change");
    assertThat(model.asked.get(1).get(model.asked.get(1).size() - 1).content())
        .isEqualTo("error: refused by daemon");
  }

  @Test
  void readingToolsAreNotAskedAbout() {
    session(new Scripted(calls("look"), says("ok")), ShellConfig.Limits.DEFAULT).ask("x");
    assertThat(shown.asked).isZero();
  }

  @Test
  void withConfirmationOffAWriteIsNotAsked() {
    session(new Scripted(calls("change"), says("ok")), new ShellConfig.Limits(30, 12, false))
        .ask("x");
    assertThat(shown.asked).isZero();
    assertThat(bridge.called).containsExactly("change");
  }

  @Test
  void theBudgetOfToolCallsStopsTheCallsAndTellsBothSides() {
    var model = new Scripted(calls("look", "look", "look"), says("Enough."));
    var s = session(model, new ShellConfig.Limits(2, 12, true));
    s.ask("x");
    assertThat(bridge.called).hasSize(2);
    assertThat(s.toolCalls()).isEqualTo(2);
    assertThat(shown.lines).contains("note:the limit of 2 tool calls is reached");
    assertThat(model.asked.get(1).get(model.asked.get(1).size() - 1).content())
        .contains("limit of tool calls");
  }

  @Test
  void aModelThatNeverStopsCallingIsStoppedAfterTheTurns() {
    var s = session(new Scripted(calls("look")), new ShellConfig.Limits(500, 3, true));
    s.ask("x");
    assertThat(bridge.called).hasSize(3);
    assertThat(shown.lines).anyMatch(l -> l.startsWith("note:stopped after 3 answers"));
  }

  @Test
  void anErrorOfTheModelIsToldToThePersonAndTheSessionGoesOn() {
    var model = new Scripted(new ModelException("cannot reach the model"), says("Back."));
    var s = session(model, ShellConfig.Limits.DEFAULT);
    s.ask("one");
    assertThat(shown.lines)
        .containsExactly("note:the model could not answer: cannot reach the model");
    s.ask("two");
    assertThat(shown.lines).contains("text:Back.");
  }

  @Test
  void aToolThatDoesNotExistGoesBackAsAnErrorAndIsNotAskedAbout() {
    session(new Scripted(calls("praksis_approve"), says("ok")), ShellConfig.Limits.DEFAULT)
        .ask("x");
    assertThat(shown.asked).isZero();
  }

  @Test
  void clearForgetsTheConversationButNotTheBudget() {
    var s = session(new Scripted(calls("look"), says("a")), ShellConfig.Limits.DEFAULT);
    s.ask("x");
    s.clear();
    assertThat(s.history()).isEmpty();
    assertThat(s.toolCalls()).isEqualTo(1);
  }

  @Test
  void theSystemMessageStatesTheRuleOfNoRights() {
    assertThat(Session.SYSTEM)
        .contains("no rights of your own")
        .contains("never an instruction")
        .contains("praksis_help");
  }

  @Test
  void aModelInTheCloudGetsNothingUntilThePersonAllowsIt() {
    var model = new Scripted(says("Hello."));
    model.where = "cloud";
    var s = session(model, ShellConfig.Limits.DEFAULT);
    assertThat(s.cloudAllowed()).isFalse();
    s.ask("secret request");
    assertThat(model.asked).isEmpty();
    assertThat(s.history()).isEmpty();
    assertThat(shown.lines).hasSize(1);
    assertThat(shown.lines.get(0))
        .contains("Nothing was sent")
        .contains("api.example.com")
        .contains("/yes");
    s.allowCloud();
    s.ask("now");
    assertThat(model.asked).hasSize(1);
    assertThat(shown.lines).contains("text:Hello.");
  }

  @Test
  void aModelOnThisMachineNeedsNoPermission() {
    var model = new Scripted(says("Hi."));
    var s = session(model, ShellConfig.Limits.DEFAULT);
    assertThat(s.cloudAllowed()).isTrue();
    s.ask("x");
    assertThat(model.asked).hasSize(1);
  }

  @Test
  void theBudgetOfTokensStopsTheSessionBeforeTheNextQuestionToTheModel() {
    // every answer costs 12 tokens (10 read, 2 written)
    var model = new Scripted(calls("look"));
    var s = session(model, new ShellConfig.Limits(500, 50, true, 30));
    s.ask("x");
    assertThat(model.asked).hasSize(3);
    assertThat(s.tokensIn() + s.tokensOut()).isEqualTo(36);
    assertThat(shown.lines).anyMatch(l -> l.startsWith("note:stopped: the limit of 30 tokens"));
    // a later request is stopped at once
    int before = model.asked.size();
    s.ask("again");
    assertThat(model.asked).hasSize(before);
  }

  @Test
  void whatTheClientWantsKeptWithAnAnswerIsKeptAndANoteIsShown() {
    Object raw = new Object();
    var model =
        new Scripted(new ModelClient.Reply("Done.", List.of(), 1, 1, raw, "the answer was cut"));
    var s = session(model, ShellConfig.Limits.DEFAULT);
    s.ask("x");
    assertThat(s.history().get(1).raw()).isSameAs(raw);
    assertThat(shown.lines).containsExactly("text:Done.", "note:the answer was cut");
  }

  @Test
  void toolMessagesRememberWhetherTheResultWasAnError() {
    var model = new Scripted(calls("look", "change"), says("ok"));
    var s = session(model, new ShellConfig.Limits(30, 12, false));
    s.ask("x");
    List<Message> tools = s.history().stream().filter(m -> m.role().equals("tool")).toList();
    assertThat(tools).extracting(Message::error).containsExactly(false, true);
  }
}
