// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;

/**
 * The person's own channel to the daemon: a few commands that talk to its Unix socket, where no
 * token reaches, as the {@code praksis} command does. They are typed by the person, handled here
 * and never given to the model: what they print is shown on the terminal and is not part of the
 * conversation, and the model has no tool and no text that runs one.
 *
 * <p>Only what is safe to decide in a line is here: allowing a request <em>once</em>, denying it,
 * and running or dropping a plan, each after the person has seen what it is. A grant that lasts
 * ({@code always}) and changes to the ruleset are decided in the desktop program or with the {@code
 * praksis} command, where the text of the grant is in front of the person.
 */
final class PersonChannel {
  /** The daemon on its socket; a seam for the tests. */
  interface Daemon {
    Response ask(Request request) throws IOException;
  }

  static final Set<String> COMMANDS =
      Set.of("/approvals", "/allow", "/deny", "/plans", "/plan", "/plan-run", "/plan-drop");

  private final Daemon daemon;
  private final String where;
  private final BufferedReader in;
  private final PrintStream out;

  PersonChannel(Daemon daemon, String where, BufferedReader in, PrintStream out) {
    this.daemon = daemon;
    this.where = where;
    this.in = in;
    this.out = out;
  }

  static boolean handles(String line) {
    String name = line.strip().split("\\s+", 2)[0];
    return COMMANDS.contains(name);
  }

  /** Runs one command line. */
  void run(String line) {
    String[] parts = line.strip().split("\\s+", 2);
    String argument = parts.length > 1 ? parts[1].strip() : "";
    try {
      switch (parts[0]) {
        case "/approvals" -> approvals();
        case "/allow" -> answer(argument, "once");
        case "/deny" -> answer(argument, "deny");
        case "/plans" -> plans();
        case "/plan" -> plan(argument, false);
        case "/plan-run" -> plan(argument, true);
        case "/plan-drop" -> drop(argument);
        default -> out.println("Unknown command.");
      }
    } catch (IOException e) {
      out.println(
          "  Cannot reach the daemon at " + Terminal.shorten(where, 120) + " (is it running?).");
    }
  }

  private Response ask(Request request) throws IOException {
    return daemon.ask(request);
  }

  private void error(Response response) {
    if (response instanceof Response.Error e) {
      out.println("  The daemon says: " + Terminal.shorten(e.message(), 300));
    } else {
      out.println("  Unexpected answer from the daemon.");
    }
  }

  // ---------------------------------------------------------------------------- approvals

  private List<Response.ApprovalList.Item> pending() throws IOException {
    Response r = ask(new Request.Approvals());
    if (r instanceof Response.ApprovalList list) {
      return list.approvals().stream().filter(i -> i.state().equals("PENDING")).toList();
    }
    error(r);
    return null;
  }

  private void approvals() throws IOException {
    List<Response.ApprovalList.Item> items = pending();
    if (items == null) {
      return;
    }
    if (items.isEmpty()) {
      out.println("  Nothing waits for you.");
      return;
    }
    items.forEach(this::show);
    out.println("  /allow <id> allows one, once; /deny <id> denies. A grant that lasts is for the");
    out.println("  desktop program or `praksis approvals`.");
  }

  private void show(Response.ApprovalList.Item i) {
    out.println(
        "  #"
            + i.id()
            + "  rule "
            + Terminal.shorten(i.ruleId(), 80)
            + " (layer "
            + Terminal.shorten(i.layer(), 40)
            + ") wants "
            + Terminal.shorten(i.capability(), 80)
            + (i.args().isEmpty()
                ? ""
                : " with " + Terminal.shorten(String.valueOf(i.args()), 200)));
  }

  private void answer(String argument, String decision) throws IOException {
    Long id = number(argument);
    if (id == null) {
      out.println(
          "  Say which request: "
              + (decision.equals("once") ? "/allow" : "/deny")
              + " <id> (see /approvals).");
      return;
    }
    List<Response.ApprovalList.Item> items = pending();
    if (items == null) {
      return;
    }
    Response.ApprovalList.Item item =
        items.stream().filter(i -> i.id() == id).findFirst().orElse(null);
    if (item == null) {
      out.println("  There is no request #" + id + " that waits (see /approvals).");
      return;
    }
    show(item);
    if (decision.equals("once")) {
      out.print("  Allow this once? [y/N] ");
      out.flush();
      if (!yes()) {
        out.println("  Not allowed.");
        return;
      }
    }
    // "once" and "deny" only; an event that the rule missed stays kept either way
    Response r = ask(new Request.Answer(id, decision, null));
    if (r instanceof Response.Done d) {
      out.println("  " + Terminal.shorten(d.message(), 300));
    } else {
      error(r);
    }
  }

  // ---------------------------------------------------------------------------- plans

  private void plans() throws IOException {
    Response r = ask(new Request.AgentPlans());
    if (!(r instanceof Response.PlanList list)) {
      error(r);
      return;
    }
    if (list.items().isEmpty()) {
      out.println("  There are no plans.");
      return;
    }
    for (Response.Plan p : list.items()) {
      out.println(
          "  "
              + Terminal.shorten(p.id(), 40)
              + "  "
              + p.status()
              + "  "
              + Terminal.shorten(p.request(), 100)
              + "  ("
              + p.steps().size()
              + " steps)");
    }
  }

  private void print(Response.Plan p) {
    out.println(
        "  Plan "
            + Terminal.shorten(p.id(), 40)
            + " ("
            + p.status()
            + "), asked: "
            + Terminal.shorten(p.request(), 200));
    int n = 0;
    for (Response.PlanStep s : p.steps()) {
      n++;
      out.println(
          "   "
              + n
              + ". "
              + Terminal.shorten(s.capability() + " " + s.args(), 200)
              + " ["
              + Terminal.clean(s.effect())
              + "]");
      if (!s.allowed()) {
        out.println("      NOT COVERED: " + Terminal.shorten(s.refusal(), 200));
      }
      if (s.warning() != null && !s.warning().isEmpty()) {
        out.println("      CHECK: " + Terminal.shorten(s.warning(), 200));
      }
      if (s.outcome() != null && !s.outcome().isEmpty()) {
        out.println("      Result: " + Terminal.shorten(s.outcome(), 200));
      }
    }
  }

  private void plan(String id, boolean run) throws IOException {
    if (id.isEmpty()) {
      out.println("  Say which plan: " + (run ? "/plan-run" : "/plan") + " <id> (see /plans).");
      return;
    }
    Response r = ask(new Request.AgentPlanShow(id));
    if (!(r instanceof Response.PlanDetail detail)) {
      error(r);
      return;
    }
    print(detail.plan());
    out.println(
        detail.runnable()
            ? "  It can be run."
            : "  It cannot be run: " + Terminal.shorten(detail.why(), 200));
    if (!run) {
      return;
    }
    if (!detail.runnable()) {
      return;
    }
    // running a plan is the person's decision: they type the id, having read the steps above
    out.print("  Type the id of the plan to run it, anything else cancels: ");
    out.flush();
    String typed = line();
    if (typed == null || !typed.strip().equals(detail.plan().id())) {
      out.println("  Not run.");
      return;
    }
    Response done = ask(new Request.AgentPlanRun(detail.plan().id()));
    if (done instanceof Response.PlanDetail d) {
      print(d.plan());
    } else {
      error(done);
    }
  }

  private void drop(String id) throws IOException {
    if (id.isEmpty()) {
      out.println("  Say which plan: /plan-drop <id> (see /plans).");
      return;
    }
    Response r = ask(new Request.AgentPlanDrop(id));
    if (r instanceof Response.Done d) {
      out.println("  " + Terminal.shorten(d.message(), 300));
    } else {
      error(r);
    }
  }

  // ---------------------------------------------------------------------------- input

  private String line() {
    try {
      return in.readLine();
    } catch (IOException e) {
      return null;
    }
  }

  private boolean yes() {
    String a = line();
    return a != null && (a.strip().equalsIgnoreCase("y") || a.strip().equalsIgnoreCase("yes"));
  }

  private static Long number(String text) {
    try {
      return Long.parseLong(text.startsWith("#") ? text.substring(1) : text);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
