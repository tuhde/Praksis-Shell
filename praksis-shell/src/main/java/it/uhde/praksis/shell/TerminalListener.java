// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import it.uhde.praksis.shell.core.Listener;
import it.uhde.praksis.shell.core.Session;
import it.uhde.praksis.shell.core.ToolBridge;
import it.uhde.praksis.shell.core.ToolCall;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Shows the conversation on a terminal and asks the person before a tool that may change things.
 */
final class TerminalListener implements Listener {
  private final PrintStream out;
  private final BufferedReader in;

  TerminalListener(PrintStream out, BufferedReader in) {
    this.out = out;
    this.in = in;
  }

  private boolean writing;

  @Override
  public void textDelta(String piece) {
    if (!writing) {
      out.println();
      writing = true;
    }
    // each piece is cleaned as it arrives, so an escape sequence cannot be put together from pieces
    out.print(Terminal.clean(piece));
    out.flush();
  }

  @Override
  public void textEnd() {
    if (writing) {
      out.println();
      writing = false;
    }
  }

  @Override
  public void toolCall(ToolCall call, boolean writes) {
    out.println(
        "  -> " + Terminal.shorten(Session.describe(call), 160) + (writes ? "  [may change]" : ""));
  }

  @Override
  public void toolResult(ToolCall call, ToolBridge.Result result) {
    out.println(
        "  <- "
            + (result.error() ? "error: " : "")
            + result.text().length()
            + " characters"
            + (result.error() ? ": " + Terminal.shorten(result.text(), 120) : ""));
  }

  @Override
  public void usage(long read, long written, long limit) {
    out.println(
        "  [tokens: "
            + Terminal.count(read)
            + " read, "
            + Terminal.count(written)
            + " written"
            + (limit > 0 ? ", limit " + Terminal.count(limit) : "")
            + "]");
  }

  @Override
  public void note(String text) {
    out.println("  ! " + Terminal.clean(text));
  }

  @Override
  public boolean confirm(ToolCall call) {
    out.print("  Allow " + Terminal.shorten(Session.describe(call), 200) + "? [y/N] ");
    out.flush();
    try {
      String answer = in.readLine();
      return answer != null
          && (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes"));
    } catch (IOException e) {
      return false;
    }
  }
}
