/*
 *  Copyright (c) VMware, Inc. 2024. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

/**
 * Simple container for credentials
 */
public interface Credentials {

  /**
   * Reference field indicating no credentials are specified
   */
  Credentials NONE = of(null, null);

  /**
   * Create a Credentials instqnce with the given username and password
   */
  static Credentials of(final String username, final String password) {
    return new Credentials() {
      @Override
      public String getUsername() {
        return username;
      }

      @Override
      public String getPassword() {
        return password;
      }
    };
  }

  /**
   * Get the username
   *
   * @return the username
   */
  String getUsername();

  /**
   * Get the password
   *
   * @return the password
   */
  String getPassword();
}
