// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * A model to talk to.
 *
 * @param name the name of the profile
 * @param api {@code ollama} or {@code openai} (the OpenAI interface, which many servers offer)
 * @param base the address of the server, scheme, host and port only
 * @param model the model
 * @param timeoutMillis how long one answer may take
 * @param maxOutputTokens the longest answer
 * @param temperature the temperature, or null for the server's default
 */
public record Profile(
    String name,
    String api,
    URI base,
    String model,
    int timeoutMillis,
    int maxOutputTokens,
    Double temperature) {

  /** Checks a profile and says what is wrong with it. */
  public static Profile of(
      String name,
      String api,
      String url,
      String model,
      String data,
      Integer timeoutMillis,
      Integer maxOutputTokens,
      Double temperature) {
    if (!"ollama".equals(api) && !"openai".equals(api)) {
      throw new IllegalArgumentException(
          "profile '" + name + "': api must be \"ollama\" or \"openai\"");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("profile '" + name + "': model is required");
    }
    // the person says where the data goes and the program holds them to it
    if (!"local".equals(data)) {
      throw new IllegalArgumentException(
          "profile '"
              + name
              + "': data must be \"local\" (a model on this machine); profiles for models in the"
              + " cloud come with the credential binding in a later version");
    }
    URI base = base(name, url);
    if (!loopback(base.getHost())) {
      throw new IllegalArgumentException(
          "profile '"
              + name
              + "': data = \"local\" needs an address of this machine, not "
              + base.getHost());
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
    return new Profile(name, api, base, model.strip(), timeout, tokens, temperature);
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
