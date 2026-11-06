// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.util.function.Function;

/** Makes the client for a profile. */
public final class Models {
  private Models() {}

  /** The client for a profile of a model on this machine. */
  public static ModelClient of(Profile profile) {
    return of(profile, name -> null);
  }

  /**
   * The client for a profile. A cloud profile gets its key from the environment or from its key
   * command, here and not earlier, so that nothing is run for a profile that is not used.
   */
  public static ModelClient of(Profile profile, Function<String, String> environment) {
    return switch (profile.api()) {
      case "ollama" -> new OllamaClient(profile);
      case "openai" -> new OpenAiClient(profile);
      case "anthropic" -> AnthropicModel.of(profile, Keys.resolve(profile, environment));
      default -> throw new IllegalArgumentException("unknown api " + profile.api());
    };
  }
}
