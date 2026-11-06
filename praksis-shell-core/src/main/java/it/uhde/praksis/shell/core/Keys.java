// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Gets the API key of a cloud profile, from an environment variable or from the first line that a
 * command prints (a password manager, KWallet). The key is never kept in the configuration, never
 * printed and never part of an error message.
 */
public final class Keys {
  private Keys() {}

  public static String resolve(Profile profile, Function<String, String> environment) {
    return resolve(profile, environment, 10);
  }

  static String resolve(Profile profile, Function<String, String> environment, int seconds) {
    String key;
    if (profile.keyEnv() != null) {
      key = environment.apply(profile.keyEnv());
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException(
            "the environment variable "
                + profile.keyEnv()
                + " is not set: it holds the API key of profile '"
                + profile.name()
                + "'");
      }
    } else if (profile.keyCommand() != null) {
      key = run(profile, seconds);
    } else {
      throw new IllegalArgumentException("profile '" + profile.name() + "' has no key source");
    }
    return key.strip();
  }

  private static String run(Profile profile, int seconds) {
    List<String> command = profile.keyCommand();
    Process p;
    try {
      p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "profile '" + profile.name() + "': cannot run key_command " + command.get(0));
    }
    try {
      p.getOutputStream().close();
      // read on another thread: a command that never prints and never ends must not hold us
      var reader =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  // the output is the key: read the first lines only, and do not show it
                  return p.getInputStream().readNBytes(4096);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
      byte[] out;
      try {
        out = reader.get(seconds, TimeUnit.SECONDS);
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
          throw new java.util.concurrent.TimeoutException();
        }
      } catch (java.util.concurrent.TimeoutException e) {
        p.destroyForcibly();
        throw new IllegalArgumentException(
            "profile '"
                + profile.name()
                + "': key_command did not finish in "
                + seconds
                + " seconds");
      } catch (java.util.concurrent.ExecutionException e) {
        throw new IllegalArgumentException(
            "profile '" + profile.name() + "': cannot read the output of key_command");
      }
      if (p.exitValue() != 0) {
        throw new IllegalArgumentException(
            "profile '" + profile.name() + "': key_command failed (exit " + p.exitValue() + ")");
      }
      String first = new String(out, StandardCharsets.UTF_8).lines().findFirst().orElse("").strip();
      if (first.isEmpty()) {
        throw new IllegalArgumentException(
            "profile '" + profile.name() + "': key_command printed nothing");
      }
      return first;
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "profile '" + profile.name() + "': cannot read the output of key_command");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      p.destroyForcibly();
      throw new IllegalArgumentException("interrupted");
    }
  }
}
