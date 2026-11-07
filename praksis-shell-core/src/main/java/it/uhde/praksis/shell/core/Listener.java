// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

/** What a front end shows and asks while a session runs. */
public interface Listener {
  /** The model says a piece of text to the person; more pieces may follow. */
  void textDelta(String piece);

  /** The text of this answer is complete (called once after the last piece, if there was any). */
  void textEnd();

  /** A tool is about to be called (shown to the person before it runs). */
  void toolCall(ToolCall call, boolean writes);

  /** A tool answered. */
  void toolResult(ToolCall call, ToolBridge.Result result);

  /** Something the person should know: a limit was reached, a call was declined. */
  void note(String text);

  /**
   * The tokens of the session so far, after a request is done.
   *
   * @param read the tokens the model has read, summed over all its answers
   * @param written the tokens it has written
   * @param limit the limit of the session, or 0 for none
   */
  default void usage(long read, long written, long limit) {}

  /**
   * Asks the person whether a tool that may change something may be called. The model does not see
   * this question and cannot answer it.
   */
  boolean confirm(ToolCall call);
}
