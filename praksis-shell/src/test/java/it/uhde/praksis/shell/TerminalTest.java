// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalTest {
  @Test
  void escapeSequencesAndOtherControlCharactersAreReplaced() {
    String hostile = "ok\u001b[2J\u001b]0;pwned\u0007 x\u009b31m y\r\nz\tw";
    String clean = Terminal.clean(hostile);
    assertThat(clean).doesNotContain("\u001b").doesNotContain("\u0007").doesNotContain("\u009b");
    assertThat(clean).doesNotContain("\r").contains("\n").contains("\t");
    assertThat(clean).startsWith("ok�[2J");
  }

  @Test
  void aLineThatShowsArgumentsIsOneShortLine() {
    String line = Terminal.shorten("a\nb " + "x".repeat(500), 40);
    assertThat(line).hasSize(40).doesNotContain("\n").endsWith("…");
    assertThat(Terminal.shorten("short", 40)).isEqualTo("short");
  }
}
