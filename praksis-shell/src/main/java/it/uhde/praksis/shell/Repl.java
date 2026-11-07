// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import it.uhde.praksis.shell.core.Session;
import it.uhde.praksis.shell.core.ToolBridge;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

/**
 * The loop of the terminal: a line is a request to the model, except the few commands that start
 * with a slash. They are the person's own and are handled here; they are never sent to the model
 * and the shell never runs a line that the model wrote as a command.
 */
final class Repl {
  private final Session session;
  private final ToolBridge tools;
  private final BufferedReader in;
  private final PrintStream out;

  Repl(Session session, ToolBridge tools, BufferedReader in, PrintStream out) {
    this.session = session;
    this.tools = tools;
    this.in = in;
    this.out = out;
  }

  void run() throws IOException {
    while (true) {
      out.print("\nyou> ");
      out.flush();
      String line = in.readLine();
      if (line == null) {
        return;
      }
      line = line.strip();
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith("/")) {
        if (!command(line)) {
          return;
        }
        continue;
      }
      session.ask(line);
    }
  }

  /** Handles a command of the person; false means leave. */
  private boolean command(String line) {
    switch (line) {
      case "/quit", "/exit" -> {
        return false;
      }
      case "/yes" -> {
        if (session.cloudAllowed() && session.where().equals("local")) {
          out.println("This model runs on this machine; there is nothing to allow.");
        } else {
          session.allowCloud();
          out.println(
              "Allowed for this session: requests and tool results go to "
                  + Terminal.clean(session.destination())
                  + ".");
        }
      }
      case "/usage" ->
          out.println(
              "Tokens in this session: "
                  + Terminal.count(session.tokensIn())
                  + " read, "
                  + Terminal.count(session.tokensOut())
                  + " written"
                  + (session.maxTokens() > 0
                      ? ", limit " + Terminal.count(session.maxTokens())
                      : ", no limit")
                  + "; "
                  + session.toolCalls()
                  + " tool calls.");
      case "/clear" -> {
        session.clear();
        out.println("The conversation is forgotten.");
      }
      case "/tools" ->
          tools
              .tools()
              .forEach(t -> out.println("  " + t.name() + (t.writes() ? "  [may change]" : "")));
      case "/help" ->
          out.println(
              "Type a request. Commands: /tools (the tools Claude has), /usage (tokens spent),"
                  + " /clear (forget the conversation), /yes (allow a model in the cloud to receive this session), /quit.\nApprovals, grants and plans are decided in the"
                  + " desktop program or with the praksis command, not here.");
      default -> out.println("Unknown command " + Terminal.shorten(line, 40) + " (try /help).");
    }
    return true;
  }
}
