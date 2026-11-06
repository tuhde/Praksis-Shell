// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * A model to talk to.
 *
 * @param name the name of the profile
 * @param api {@code ollama}, {@code openai} (the OpenAI interface, which many servers offer) or
 *     {@code anthropic}
 * @param base the address of the server, scheme, host and port only
 * @param model the model
 * @param data {@code local} or {@code cloud}: where the data goes, said by the person and held to
 * @param timeoutMillis how long one answer may take
 * @param maxOutputTokens the longest answer
 * @param temperature the temperature, or null for the server's default
 * @param keyEnv for a cloud profile, the name of the environment variable that holds the key
 * @param keyCommand for a cloud profile, a command that prints the key
 */
public record Profile(
    String name,
    String api,
    URI base,
    String model,
    String data,
    int timeoutMillis,
    int maxOutputTokens,
    Double temperature,
    String keyEnv,
    List<String> keyCommand) {

  /** A profile for a model on this machine. */
  public static Profile of(
      String name,
      String api,
      String url,
      String model,
      String data,
      Integer timeoutMillis,
      Integer maxOutputTokens,
      Double temperature) {
    return of(name, api, url, model, data, timeoutMillis, maxOutputTokens, temperature, null, null);
  }

  /** Checks a profile and says what is wrong with it. */
  public static Profile of(
      String name,
      String api,
      String url,
      String model,
      String data,
      Integer timeoutMillis,
      Integer maxOutputTokens,
      Double temperature,
      String keyEnv,
      List<String> keyCommand) {
    if (!"ollama".equals(api) && !"openai".equals(api) && !"anthropic".equals(api)) {
      throw new IllegalArgumentException(
          "profile '" + name + "': api must be \"ollama\", \"openai\" or \"anthropic\"");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("profile '" + name + "': model is required");
    }
    boolean cloud = "anthropic".equals(api);
    // the person says where the data goes and the program holds them to it
    if (cloud && !"cloud".equals(data)) {
      throw new IllegalArgumentException(
          "profile '" + name + "': api \"anthropic\" needs data = \"cloud\"");
    }
    if (!cloud && !"local".equals(data)) {
      throw new IllegalArgumentException(
          "profile '"
              + name
              + "': data must be \"local\" (a model on this machine); a model in the cloud is"
              + " api = \"anthropic\" with data = \"cloud\"");
    }
    URI base =
        base(name, cloud && (url == null || url.isBlank()) ? "https://api.anthropic.com" : url);
    if (cloud) {
      if (!"https".equals(base.getScheme())) {
        throw new IllegalArgumentException(
            "profile '"
                + name
                + "': a model in the cloud needs an https:// address, because the"
                + " key is sent with every request");
      }
      if (temperature != null) {
        throw new IllegalArgumentException(
            "profile '"
                + name
                + "': the Anthropic API no longer accepts temperature; leave it out");
      }
      boolean hasEnv = keyEnv != null && !keyEnv.isBlank();
      boolean hasCommand = keyCommand != null && !keyCommand.isEmpty();
      if (hasEnv == hasCommand) {
        throw new IllegalArgumentException(
            "profile '"
                + name
                + "': give exactly one of key_env (the name of an environment"
                + " variable) and key_command (a command that prints the key)");
      }
    } else {
      if (!loopback(base.getHost())) {
        throw new IllegalArgumentException(
            "profile '"
                + name
                + "': data = \"local\" needs an address of this machine, not "
                + base.getHost());
      }
      if (keyEnv != null || keyCommand != null) {
        throw new IllegalArgumentException(
            "profile '" + name + "': key_env and key_command are for api = \"anthropic\"");
      }
    }
    int timeout = timeoutMillis == null ? 120_000 : timeoutMillis;
    int tokens = maxOutputTokens == null ? 2048 : maxOutputTokens;
    if (timeout < 1000 || timeout > 600_000) {
      throw new IllegalArgumentException(
          "profile '" + name + "': timeout_ms must be between 1000 and 600000");
    }
    if (tokens < 16 || tokens > 100_000) {
      throw new IllegalArgumentException(
          "profile '" + name + "': max_output_tokens must be between 16 and 100000");
    }
    return new Profile(
        name,
        api,
        base,
        model.strip(),
        data,
        timeout,
        tokens,
        temperature,
        cloud ? keyEnv : null,
        cloud && keyCommand != null ? List.copyOf(keyCommand) : null);
  }

  /** Whether the data leaves this machine. */
  public boolean cloud() {
    return "cloud".equals(data);
  }

  @Override
  public String toString() {
    // no key source in the text: it could name a secret store entry
    return "Profile[" + name + ", " + api + ", " + base + ", " + model + ", " + data + "]";
  }

  private static URI base(String name, String url) {
    try {
      URI uri = new URI(url == null ? "" : url.strip());
      if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
        throw new IllegalArgumentException(
            "profile '" + name + "': url must start with http:// or https://");
      }
      if (uri.getHost() == null || uri.getUserInfo() != null) {
        throw new IllegalArgumentException(
            "profile '" + name + "': url needs a host and no user name or password");
      }
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("profile '" + name + "': url is not an address");
    }
  }

  private static boolean loopback(String host) {
    String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    if (h.equalsIgnoreCase("localhost")) {
      return true;
    }
    // only literal addresses: a name is not looked up, because it could be made to point anywhere
    if (!h.matches("[0-9.]+|[0-9a-fA-F:]+")) {
      return false;
    }
    try {
      return InetAddress.getByName(h).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
