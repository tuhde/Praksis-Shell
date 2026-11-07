// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.api;

/** One conversation with a model that works with Praksis through the MCP server. */
public interface Conversation extends AutoCloseable {
  /**
   * What the person should see of the setup.
   *
   * @param model the name of the model
   * @param where {@code local} or {@code cloud}
   * @param destination the host that receives the conversation, or {@code this machine}
   * @param tools how many tools the model has
   * @param mode the mode of the MCP server (read-only, propose or commands)
   */
  record Info(String model, String where, String destination, int tools, String mode) {}

  Info info();

  /** Handles one request of the person; blocks until the model is done. */
  void ask(String request);

  /** Makes the model forget the conversation. */
  void clear();

  /**
   * Whether the conversation may be sent to the model (always true for a model on this machine).
   */
  boolean cloudAllowed();

  /**
   * The person allows this session to send the conversation to a model in the cloud. Only the
   * program calls this, from something the person did; nothing the model says can.
   */
  void allowCloud();

  @Override
  void close();
}
