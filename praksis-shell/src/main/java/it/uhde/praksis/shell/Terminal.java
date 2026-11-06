// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell;

/** Keeps what is printed from changing the terminal. */
final class Terminal {
  private Terminal() {}

  /**
   * Text from a model or a tool can contain escape sequences (a mail subject that the model
   * repeats), which could move the cursor, change the title or hide what was printed. Control
   * characters other than a line break and a tab are replaced.
   */
  static String clean(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints()
        .forEach(
            c -> {
              boolean control = c < 0x20 || (c >= 0x7f && c <= 0x9f);
              if (control && c != '\n' && c != '\t') {
                out.append('�');
              } else {
                out.appendCodePoint(c);
              }
            });
    return out.toString();
  }

  /** At most this many characters, for a line that shows arguments. */
  static String shorten(String text, int max) {
    String t = clean(text).replace('\n', ' ');
    return t.length() <= max ? t : t.substring(0, max - 1) + "…";
  }
}
