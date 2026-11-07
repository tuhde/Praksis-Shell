// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.api;

/**
 * What a program shows and asks while the engine works on a request. The methods are called on the
 * thread of the engine, not on the thread of the program's window.
 */
public interface ConversationListener {
  /** The model says a piece of text; more pieces may follow. */
  void textDelta(String piece);

  /** The text of this answer is complete. */
  void textEnd();

  /**
   * A tool is about to be called.
   *
   * @param name the tool
   * @param arguments its arguments as a line of text
   * @param writes whether it may change something
   */
  void toolCall(String name, String arguments, boolean writes);

  /**
   * A tool answered.
   *
   * @param name the tool
   * @param length the length of its answer in characters
   * @param error whether the answer is an error
   * @param shortText for an error, what it said (cut), else empty
   */
  void toolResult(String name, int length, boolean error, String shortText);

  /** Something the person should know: a limit was reached, a call was declined. */
  void note(String text);

  /** The tokens of the session so far; {@code limit} is 0 for none. */
  void usage(long read, long written, long limit);

  /**
   * Asks the person whether a tool that may change something may be called. The model does not see
   * the question and cannot answer it.
   */
  boolean confirm(String name, String arguments);
}
