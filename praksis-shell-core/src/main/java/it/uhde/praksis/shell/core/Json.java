// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/** The one mapper of the shell. */
final class Json {
  static final ObjectMapper MAPPER = new ObjectMapper();

  private Json() {}

  static String write(Object o) throws ModelException {
    try {
      return MAPPER.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      throw new ModelException("cannot write the request: " + e.getOriginalMessage());
    }
  }

  static Map<String, Object> map(String text) {
    try {
      Map<String, Object> m = MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {});
      return m == null ? Map.of() : m;
    } catch (JsonProcessingException e) {
      return Map.of();
    }
  }
}
