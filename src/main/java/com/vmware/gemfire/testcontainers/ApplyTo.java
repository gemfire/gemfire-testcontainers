/*
 *  Copyright (c) VMware, Inc. 2023. All rights reserved.
 */

package com.vmware.gemfire.testcontainers;

/**
 * Enum used to apply configuration to different types of members where appropriate.
 */
public enum ApplyTo {
  LOCATOR,
  SERVER,
  LOCATOR_AND_SERVER
}
