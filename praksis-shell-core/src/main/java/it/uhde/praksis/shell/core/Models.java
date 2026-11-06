// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

/** Makes the client for a profile. */
public final class Models {
  private Models() {}

  public static ModelClient of(Profile profile) {
    return switch (profile.api()) {
      case "ollama" -> new OllamaClient(profile);
      case "openai" -> new OpenAiClient(profile);
      default -> throw new IllegalArgumentException("unknown api " + profile.api());
    };
  }
}
