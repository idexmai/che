/*
 * Copyright (c) 2012-2018 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.che.selenium.core.client;

import org.eclipse.che.api.core.model.workspace.Workspace;
import org.eclipse.che.api.core.model.workspace.WorkspaceStatus;
import org.eclipse.che.api.core.model.workspace.runtime.Server;
import org.eclipse.che.api.workspace.shared.dto.WorkspaceConfigDto;
import org.eclipse.che.api.workspace.shared.dto.WorkspaceDto;
import org.eclipse.che.commons.annotation.Nullable;
import org.eclipse.che.selenium.core.user.TestUser;
import org.eclipse.che.selenium.core.workspace.MemoryMeasure;

import java.util.List;

public interface TestWorkspaceServiceClient {

  /** Returns the list of workspaces names that belongs to the user. */
  List<String> getAll() throws Exception;

  /** Stops workspace. */
  void stop(String workspaceName, String userName) throws Exception;

  /** Returns workspace of default user by its name. */
  Workspace getByName(String workspace, String username) throws Exception;

  /** Indicates if workspace exists. */
  boolean exists(String workspace, String username) throws Exception;

  /** Deletes workspace of default user. */
  void delete(String workspaceName, String userName) throws Exception;

  /** Waits needed status. */
  void waitStatus(String workspaceName, String userName, WorkspaceStatus expectedStatus) throws Exception;

  /** Creates a new workspace. */
  abstract Workspace createWorkspace(String workspaceName, int memory, MemoryMeasure memoryUnit, WorkspaceConfigDto workspace) throws Exception;

  /** Sends start workspace request. */
  void sendStartRequest(String workspaceId, String workspaceName) throws Exception;

  /** Starts workspace. */
  abstract void start(String workspaceId, String workspaceName, TestUser workspaceOwner) throws Exception;

  /** Gets workspace by its id. */
  WorkspaceDto getById(String workspaceId) throws Exception;

  /** Gets workspace status by id. */
  WorkspaceStatus getStatus(String workspaceId) throws Exception;

  @Deprecated
  @Nullable
  String getServerAddressByPort(String workspaceId, int port) throws Exception;

  @Nullable
  Server getServerFromDevMachineBySymbolicName(String workspaceId, String serverName) throws Exception;

  void ensureRunningStatus(Workspace workspace) ;

  void deleteFactoryWorkspaces(String originalName, String username) throws Exception;
}
