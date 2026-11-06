// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.shell.core;

/** The model could not be asked, or its answer could not be used. */
public class ModelException extends Exception {
  private static final long serialVersionUID = 1L;

  public ModelException(String message) {
    super(message);
  }

  public ModelException(String message, Throwable cause) {
    super(message, cause);
  }
}
