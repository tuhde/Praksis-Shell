// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.api;

import java.nio.file.Path;
import java.util.function.Function;

/** Opens conversations. The engine registers one as a service. */
public interface EngineProvider {
  /**
   * Opens a conversation as the configuration says: the model, the MCP server and the limits.
   *
   * @param config the file, in the format of {@code shell.toml}
   * @param environment looks up an environment variable (the token and the API key are in it)
   * @param listener what is shown and asked while the engine works
   */
  Conversation open(
      Path config, Function<String, String> environment, ConversationListener listener)
      throws EngineException;
}
