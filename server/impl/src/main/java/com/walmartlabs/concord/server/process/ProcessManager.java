package com.walmartlabs.concord.server.process;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.project.InternalConstants;
import com.walmartlabs.concord.server.ConcordApplicationException;
import com.walmartlabs.concord.server.agent.AgentManager;
import com.walmartlabs.concord.server.org.ResourceAccessLevel;
import com.walmartlabs.concord.server.org.project.ProjectAccessManager;
import com.walmartlabs.concord.server.org.project.RepositoryDao;
import com.walmartlabs.concord.server.org.project.RepositoryEntry;
import com.walmartlabs.concord.server.process.form.ConcordFormService;
import com.walmartlabs.concord.server.process.form.ConcordFormService.FormSubmitResult;
import com.walmartlabs.concord.server.process.logs.LogManager;
import com.walmartlabs.concord.server.process.pipelines.ForkPipeline;
import com.walmartlabs.concord.server.process.pipelines.ProcessPipeline;
import com.walmartlabs.concord.server.process.pipelines.ResumePipeline;
import com.walmartlabs.concord.server.process.pipelines.processors.Chain;
import com.walmartlabs.concord.server.process.queue.ProcessQueueDao;
import com.walmartlabs.concord.server.process.queue.ProcessQueueDao.IdAndStatus;
import com.walmartlabs.concord.server.process.queue.ProcessQueueEntry;
import com.walmartlabs.concord.server.process.state.ProcessCheckpointManager;
import com.walmartlabs.concord.server.process.state.ProcessStateManager;
import com.walmartlabs.concord.server.security.Roles;
import com.walmartlabs.concord.server.security.UserPrincipal;
import com.walmartlabs.concord.server.security.sessionkey.SessionKeyPrincipal;
import org.apache.shiro.authz.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import static com.walmartlabs.concord.server.process.state.ProcessStateManager.path;

@Named
public class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    private final ProcessQueueDao queueDao;
    private final ProcessStateManager stateManager;
    private final AgentManager agentManager;
    private final LogManager logManager;
    private final ConcordFormService formService;
    private final ProjectAccessManager projectAccessManager;
    private final ProcessCheckpointManager checkpointManager;
    private final PayloadManager payloadManager;
    private final RepositoryDao repositoryDao;

    private final Chain processPipeline;
    private final Chain resumePipeline;
    private final Chain forkPipeline;

    private static final List<ProcessStatus> SERVER_PROCESS_STATUSES = Arrays.asList(
            ProcessStatus.ENQUEUED,
            ProcessStatus.PREPARING,
            ProcessStatus.SUSPENDED);

    private static final List<ProcessStatus> TERMINATED_PROCESS_STATUSES = Arrays.asList(
            ProcessStatus.CANCELLED,
            ProcessStatus.FAILED,
            ProcessStatus.FINISHED,
            ProcessStatus.TIMED_OUT);

    private static final List<ProcessStatus> AGENT_PROCESS_STATUSES = Arrays.asList(
            ProcessStatus.STARTING,
            ProcessStatus.RUNNING,
            ProcessStatus.RESUMING);

    private static final Set<ProcessStatus> RESTORE_ALLOWED_STATUSES = new HashSet<>(Arrays.asList(
            ProcessStatus.FAILED,
            ProcessStatus.FINISHED,
            ProcessStatus.SUSPENDED,
            ProcessStatus.TIMED_OUT,
            ProcessStatus.CANCELLED));

    @Inject
    public ProcessManager(ProcessQueueDao queueDao,
                          ProcessStateManager stateManager,
                          AgentManager agentManager,
                          LogManager logManager,
                          ConcordFormService formService,
                          ProjectAccessManager projectAccessManager,
                          ProcessCheckpointManager checkpointManager,
                          PayloadManager payloadManager,
                          RepositoryDao repositoryDao,
                          ProcessPipeline processPipeline,
                          ResumePipeline resumePipeline,
                          ForkPipeline forkPipeline) {

        this.queueDao = queueDao;
        this.stateManager = stateManager;
        this.agentManager = agentManager;
        this.logManager = logManager;
        this.formService = formService;
        this.projectAccessManager = projectAccessManager;
        this.checkpointManager = checkpointManager;
        this.payloadManager = payloadManager;
        this.repositoryDao = repositoryDao;

        this.processPipeline = processPipeline;
        this.resumePipeline = resumePipeline;
        this.forkPipeline = forkPipeline;
    }

    public ProcessQueueEntry nextProcess(Map<String, Object> capabilities) {
        return queueDao.poll(capabilities);
    }

    public ProcessResult start(Payload payload, boolean sync) {
        return start(processPipeline, payload, sync);
    }

    public ProcessResult startFork(Payload payload, boolean sync) {
        return start(forkPipeline, payload, sync);
    }

    public void resume(Payload payload) {
        resumePipeline.process(payload);
    }

    public void kill(ProcessKey processKey) {
        ProcessEntry e = queueDao.get(processKey);
        if (e == null) {
            throw new ProcessException(null, "Process not found: " + processKey, Status.NOT_FOUND);
        }

        assertKillRights(e);

        ProcessStatus s = e.status();
        if (TERMINATED_PROCESS_STATUSES.contains(s)) {
            return;
        }

        if (cancel(processKey, s, SERVER_PROCESS_STATUSES)) {
            return;
        }

        agentManager.killProcess(processKey);
    }

    public void killCascade(PartialProcessKey processKey) {
        ProcessEntry e = queueDao.get(processKey);
        if (e == null) {
            throw new ProcessException(null, "Process not found: " + processKey, Status.NOT_FOUND);
        }

        assertKillRights(e);

        List<IdAndStatus> l = null;
        boolean updated = false;
        while (!updated) {
            l = queueDao.getCascade(processKey);
            List<ProcessKey> keys = filterProcessKeys(l, SERVER_PROCESS_STATUSES);
            updated = keys.isEmpty() || queueDao.updateStatus(keys, ProcessStatus.CANCELLED, SERVER_PROCESS_STATUSES);
        }

        List<ProcessKey> keys = filterProcessKeys(l, AGENT_PROCESS_STATUSES);
        if (!keys.isEmpty()) {
            agentManager.killProcess(keys);
        }
    }

    public void restoreFromCheckpoint(ProcessKey processKey, UUID checkpointId) {
        ProcessEntry entry = queueDao.get(processKey);

        checkpointManager.assertProcessAccess(entry);

        if (checkpointId == null) {
            throw new ConcordApplicationException("'checkpointId' is mandatory");
        }

        ProcessStatus s = entry.status();
        if (!RESTORE_ALLOWED_STATUSES.contains(s)) {
            throw new ConcordApplicationException("Unable to restore a checkpoint, the process is " + s);
        }

        String eventName = checkpointManager.restoreCheckpoint(processKey, checkpointId);
        if (eventName == null) {
            throw new ConcordApplicationException("Checkpoint " + checkpointId + " not found");
        }

        Payload payload;
        try {
            payload = payloadManager.createResumePayload(processKey, eventName, null);
        } catch (IOException e) {
            log.error("restore ['{}', '{}'] -> error creating a payload: {}", processKey, eventName, e);
            throw new ConcordApplicationException("Error creating a payload", e);
        }

        queueDao.updateStatus(processKey, ProcessStatus.SUSPENDED, Collections.singletonMap("checkpointId", checkpointId));

        resume(payload);
    }

    public void updateStatus(ProcessKey processKey, String agentId, ProcessStatus status) {
        assertUpdateRights(processKey);

        // TODO determine the correct status on the agent?
        if (status == ProcessStatus.FINISHED && isSuspended(processKey)) {
            status = ProcessStatus.SUSPENDED;
        }

        if (status == ProcessStatus.CANCELLED && isFinished(processKey)) {
            log.info("updateStatus [{}, '{}', {}] -> ignored, process finished", processKey, agentId, status);
            return;
        }

        queueDao.updateAgentId(processKey, agentId, status);
        logManager.info(processKey, "Process status: {}", status);

        log.info("updateStatus [{}, '{}', {}] -> done", processKey, agentId, status);
    }

    public ProcessEntry assertProcess(UUID instanceId) {
        ProcessEntry p = queueDao.get(PartialProcessKey.from(instanceId));
        if (p == null) {
            throw new ConcordApplicationException("Process instance not found", Response.Status.NOT_FOUND);
        }
        return p;
    }

    private boolean isSuspended(ProcessKey processKey) {
        String resource = path(InternalConstants.Files.JOB_ATTACHMENTS_DIR_NAME,
                InternalConstants.Files.JOB_STATE_DIR_NAME,
                InternalConstants.Files.SUSPEND_MARKER_FILE_NAME);

        return stateManager.exists(processKey, resource);
    }

    private boolean isFinished(PartialProcessKey processKey) {
        UUID instanceId = processKey.getInstanceId();

        ProcessStatus status = queueDao.getStatus(instanceId);
        if (status == null) {
            return true;
        }
        return TERMINATED_PROCESS_STATUSES.contains(status);
    }

    private ProcessResult start(Chain pipeline, Payload payload, boolean sync) {
        assertRepositoryDisabled(payload);

        ProcessKey processKey = payload.getProcessKey();

        try {
            pipeline.process(payload);
        } catch (ProcessException e) {
            throw e;
        } catch (Exception e) {
            log.error("start ['{}'] -> error starting the process", processKey, e);
            throw new ProcessException(processKey, "Error starting the process", e, Status.INTERNAL_SERVER_ERROR);
        }

        Map<String, Object> out = null;
        if (sync) {
            Map<String, Object> args = readArgs(processKey);
            out = process(processKey, args);
        }

        UUID instanceId = processKey.getInstanceId();
        return new ProcessResult(instanceId, out);
    }

    private void assertRepositoryDisabled(Payload payload) {
        if (payload.getHeader(Payload.REPOSITORY_ID) != null) {
            UUID repoId = payload.getHeader(Payload.REPOSITORY_ID);
            RepositoryEntry repo = repositoryDao.get(repoId);
            if (repo.isDisabled()) {
                throw new ConcordApplicationException("Repository is disabled -> " + repo.getName());
            }

        }
    }

    private Map<String, Object> process(ProcessKey processKey, Map<String, Object> params) {
        while (true) {
            ProcessEntry entry = queueDao.get(processKey);
            ProcessStatus status = entry.status();

            if (status == ProcessStatus.SUSPENDED) {
                wakeUpProcess(processKey, params);
            } else if (status == ProcessStatus.FAILED || status == ProcessStatus.CANCELLED) {
                throw new ProcessException(processKey, "Process error: " + status, Status.INTERNAL_SERVER_ERROR);
            } else if (status == ProcessStatus.FINISHED) {
                return readOutValues(entry);
            }

            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wakeUpProcess(ProcessKey processKey, Map<String, Object> data) {
        FormSubmitResult r = formService.submitNext(processKey, data);
        if (r != null && !r.isValid()) {
            String error = "n/a";
            if (r.getErrors() != null) {
                error = r.getErrors().stream().map(e -> e.getFieldName() + ": " + e.getError()).collect(Collectors.joining(","));
            }
            throw new ProcessException(processKey, "Form '" + r.getFormName() + "' submit error: " + error, Status.BAD_REQUEST);
        }
    }

    private boolean cancel(ProcessKey processKey, ProcessStatus current, List<ProcessStatus> expected) {
        boolean found = false;
        for (ProcessStatus s : expected) {
            if (current == s) {
                found = true;
                break;
            }
        }

        return found && queueDao.updateStatus(processKey, current, ProcessStatus.CANCELLED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readOutValues(ProcessEntry entry) {
        Map<String, Object> meta = entry.meta();
        return meta != null ? (Map<String, Object>) meta.get("out") : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(ProcessKey processKey) {
        String resource = InternalConstants.Files.REQUEST_DATA_FILE_NAME;
        Optional<Map<String, Object>> o = stateManager.get(processKey, resource, in -> {
            try {
                ObjectMapper om = new ObjectMapper();

                Map<String, Object> cfg = om.readValue(in, Map.class);
                Map<String, Object> args = (Map<String, Object>) cfg.get(InternalConstants.Request.ARGUMENTS_KEY);

                return Optional.ofNullable(args);
            } catch (IOException e) {
                throw new ConcordApplicationException("Error while reading request data", e);
            }
        });
        return o.orElse(Collections.emptyMap());
    }

    private void assertKillRights(ProcessEntry e) {
        if (Roles.isAdmin()) {
            return;
        }

        UserPrincipal p = UserPrincipal.assertCurrent();
        if (p.getId().equals(e.initiatorId())) {
            // process owners can kill their own processes
            return;
        }

        UUID projectId = e.projectId();
        if (projectId != null) {
            // only org members with WRITER rights can kill the process
            projectAccessManager.assertProjectAccess(projectId, ResourceAccessLevel.WRITER, true);
            return;
        }

        throw new UnauthorizedException("The current user (" + p.getUsername() + ") does not have permissions " +
                "to kill the process: " + e.instanceId());
    }

    private void assertUpdateRights(PartialProcessKey processKey) {
        if (Roles.isAdmin() || Roles.isGlobalWriter()) {
            return;
        }

        UserPrincipal p = UserPrincipal.assertCurrent();

        SessionKeyPrincipal s = SessionKeyPrincipal.getCurrent();
        if (s != null && processKey.partOf(s.getProcessKey())) {
            // processes can update their own statuses
            return;
        }

        throw new UnauthorizedException("The current user (" + p.getUsername() + ") does not have permissions " +
                "to update the process status: " + processKey);
    }

    private static List<ProcessKey> filterProcessKeys(List<IdAndStatus> l, List<ProcessStatus> expected) {
        return l.stream()
                .filter(r -> expected.contains(r.getStatus()))
                .map(IdAndStatus::getProcessKey)
                .collect(Collectors.toList());
    }

    public static final class ProcessResult implements Serializable {

        private final UUID instanceId;
        private final Map<String, Object> out;

        public ProcessResult(UUID instanceId, Map<String, Object> out) {
            this.instanceId = instanceId;
            this.out = out;
        }

        public UUID getInstanceId() {
            return instanceId;
        }

        public Map<String, Object> getOut() {
            return out;
        }
    }
}
