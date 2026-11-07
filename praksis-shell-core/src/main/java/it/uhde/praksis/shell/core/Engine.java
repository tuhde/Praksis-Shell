// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import it.uhde.praksis.shell.api.Conversation;
import it.uhde.praksis.shell.api.ConversationListener;
import it.uhde.praksis.shell.api.EngineException;
import it.uhde.praksis.shell.api.EngineProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * The engine for a program that shows the conversation: it reads the configuration, starts the MCP
 * server of Praksis and gives back a {@link Conversation}. It is registered as a service of {@link
 * EngineProvider}, so that a program can load it from a jar of its own.
 */
public final class Engine implements EngineProvider {
  /**
   * Runs with the class loader of the engine as the context class loader. The libraries find their
   * parts with {@code ServiceLoader} through it, and the program that loads the engine in a loader
   * of its own calls from threads that have the program's.
   */
  private static <T> T inEngineLoader(java.util.function.Supplier<T> job) {
    Thread thread = Thread.currentThread();
    ClassLoader before = thread.getContextClassLoader();
    thread.setContextClassLoader(Engine.class.getClassLoader());
    try {
      return job.get();
    } finally {
      thread.setContextClassLoader(before);
    }
  }

  @Override
  public Conversation open(
      Path config, Function<String, String> environment, ConversationListener listener)
      throws EngineException {
    try {
      return inEngineLoader(
          () -> {
            try {
              return openIn(config, environment, listener);
            } catch (EngineException e) {
              throw new IllegalStateException(e);
            }
          });
    } catch (IllegalStateException e) {
      if (e.getCause() instanceof EngineException engine) {
        throw engine;
      }
      throw e;
    }
  }

  private Conversation openIn(
      Path config, Function<String, String> environment, ConversationListener listener)
      throws EngineException {
    ShellConfig shell;
    ModelClient model;
    McpBridge tools;
    try {
      shell = ShellConfig.load(config);
      model = Models.of(shell.profile(), environment);
      tools = McpBridge.start(shell, environment);
    } catch (IOException | IllegalArgumentException | IllegalStateException e) {
      throw new EngineException(e.getMessage(), e);
    }
    Session session = new Session(model, tools, new Adapter(listener), shell.limits());
    Conversation.Info info =
        new Conversation.Info(
            model.name(),
            model.where(),
            model.destination(),
            tools.tools().size(),
            shell.mcpMode());
    return new Conversation() {
      @Override
      public Info info() {
        return info;
      }

      @Override
      public void ask(String request) {
        inEngineLoader(
            () -> {
              session.ask(request);
              return null;
            });
      }

      @Override
      public void clear() {
        session.clear();
      }

      @Override
      public boolean cloudAllowed() {
        return session.cloudAllowed();
      }

      @Override
      public void allowCloud() {
        session.allowCloud();
      }

      @Override
      public void close() {
        inEngineLoader(
            () -> {
              tools.close();
              return null;
            });
      }
    };
  }

  /** The listener of the program as the listener of the session. */
  private record Adapter(ConversationListener out) implements Listener {
    @Override
    public void textDelta(String piece) {
      out.textDelta(piece);
    }

    @Override
    public void textEnd() {
      out.textEnd();
    }

    @Override
    public void toolCall(ToolCall call, boolean writes) {
      out.toolCall(call.name(), call.arguments().toString(), writes);
    }

    @Override
    public void toolResult(ToolCall call, ToolBridge.Result result) {
      String text = result.error() ? result.text() : "";
      out.toolResult(
          call.name(),
          result.text().length(),
          result.error(),
          text.length() > 300 ? text.substring(0, 300) + "…" : text);
    }

    @Override
    public void note(String text) {
      out.note(text);
    }

    @Override
    public void usage(long read, long written, long limit) {
      out.usage(read, written, limit);
    }

    @Override
    public boolean confirm(ToolCall call) {
      return out.confirm(call.name(), call.arguments().toString());
    }
  }
}
