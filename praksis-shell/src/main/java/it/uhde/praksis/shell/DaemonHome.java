// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import it.uhde.praksis.shell.core.ShellConfig;
import java.nio.file.Path;
import java.util.function.Function;

/** Where the daemon of the person lives, found the way the {@code praksis} command finds it. */
final class DaemonHome {
  private DaemonHome() {}

  /**
   * The home directory: {@code [daemon] home}, then {@code [daemon] identity}, then {@code
   * $PRAKSIS_HOME}, then {@code $PRAKSIS_IDENTITY}, else {@code ~/.praksis}.
   */
  static Path of(ShellConfig config, Function<String, String> env, Path userHome) {
    if (config.daemonHome() != null) {
      return expand(config.daemonHome(), userHome);
    }
    if (config.daemonIdentity() != null) {
      return userHome.resolve(".praksis-" + config.daemonIdentity());
    }
    String home = env.apply("PRAKSIS_HOME");
    if (home != null && !home.isBlank()) {
      return expand(home, userHome);
    }
    String identity = env.apply("PRAKSIS_IDENTITY");
    if (identity != null && !identity.isBlank()) {
      return userHome.resolve(".praksis-" + identity);
    }
    return userHome.resolve(".praksis");
  }

  static Path socket(Path home) {
    return home.resolve("praksis.sock");
  }

  private static Path expand(String text, Path userHome) {
    if (text.equals("~")) {
      return userHome;
    }
    return text.startsWith("~/") ? userHome.resolve(text.substring(2)) : Path.of(text);
  }
}
