// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.shell.core.McpBridge;
import it.uhde.praksis.shell.core.ModelClient;
import it.uhde.praksis.shell.core.Models;
import it.uhde.praksis.shell.core.Session;
import it.uhde.praksis.shell.core.ShellConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** The Claude Shell in a terminal. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws IOException {
    ShellConfig config;
    McpBridge tools;
    ModelClient model;
    try {
      config = ShellConfig.load(configFile());
      model = Models.of(config.profile(), System::getenv);
      tools = McpBridge.start(config, System::getenv);
    } catch (IllegalArgumentException | IllegalStateException e) {
      System.err.println("praksis-shell: " + e.getMessage());
      System.exit(2);
      return;
    }
    var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    var listener = new TerminalListener(System.out, in);
    var session = new Session(model, tools, listener, config.limits());
    Path home = DaemonHome.of(config, System::getenv, Path.of(System.getProperty("user.home")));
    PersonChannel channel =
        new PersonChannel(
            request -> {
              try (var client = Client.connect(DaemonHome.socket(home))) {
                return client.send(request);
              }
            },
            DaemonHome.socket(home).toString(),
            in,
            System.out);
    System.out.println(
        "Praksis Shell. Model "
            + model.name()
            + " ("
            + model.where()
            + "), "
            + tools.tools().size()
            + " tools, mode "
            + config.mcpMode()
            + (config.limits().confirmTools()
                ? ", asks before a tool that may change something"
                : "")
            + ". /help for help.");
    if (model.where().equals("cloud")) {
      System.out.println(
          "This model runs in the cloud ("
              + model.destination()
              + "). Nothing is sent until you type /yes.");
    }
    try {
      new Repl(session, tools, channel, in, System.out).run();
    } finally {
      tools.close();
      System.out.println(
          "\n"
              + session.toolCalls()
              + " tool calls, "
              + session.tokensIn()
              + " tokens read, "
              + session.tokensOut()
              + " written.");
    }
  }

  static Path configFile() {
    String given = System.getenv("PRAKSIS_SHELL_CONFIG");
    if (given != null && !given.isBlank()) {
      return Path.of(given);
    }
    String xdg = System.getenv("XDG_CONFIG_HOME");
    Path base =
        xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".config");
    return base.resolve("praksis-shell").resolve("shell.toml");
  }
}
