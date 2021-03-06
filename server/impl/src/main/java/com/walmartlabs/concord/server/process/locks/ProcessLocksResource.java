package com.walmartlabs.concord.server.process.locks;

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

import com.walmartlabs.concord.server.ConcordApplicationException;
import com.walmartlabs.concord.server.jooq.enums.ProcessLockScope;
import com.walmartlabs.concord.server.metrics.WithTimer;
import com.walmartlabs.concord.server.process.PartialProcessKey;
import com.walmartlabs.concord.server.process.ProcessEntry;
import com.walmartlabs.concord.server.process.ProcessKey;
import com.walmartlabs.concord.server.process.queue.AbstractWaitCondition;
import com.walmartlabs.concord.server.process.queue.ProcessLockCondition;
import com.walmartlabs.concord.server.process.queue.ProcessQueueDao;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;
import org.sonatype.siesta.Resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.UUID;

@Named
@Singleton
@Api(value = "Process Locks", authorizations = {@Authorization("session_key")})
@Path("/api/v1/process")
public class ProcessLocksResource implements Resource {

    private final ProcessQueueDao queueDao;
    private final ProcessLocksDao dao;

    @Inject
    public ProcessLocksResource(ProcessQueueDao queueDao, ProcessLocksDao dao) {
        this.queueDao = queueDao;
        this.dao = dao;
    }

    /**
     * Acquires the lock if it is available and returns the LockResult.acquired = true.
     * If the lock is not available then this method will return the LockResult.acquired = false.
     */
    @POST
    @ApiOperation("Try lock")
    @Path("/{processInstanceId}/lock/{lockName}")
    @Produces(MediaType.APPLICATION_JSON)
    @WithTimer
    public LockResult tryLock(@PathParam("processInstanceId") UUID instanceId,
                              @PathParam("lockName") String lockName,
                              @QueryParam("scope") @DefaultValue("PROJECT") ProcessLockScope scope) {

        ProcessEntry e = assertProcess(instanceId);

        LockEntry lock = dao.tryLock(e.instanceId(), e.orgId(), e.projectId(), scope, lockName);
        boolean acquired = lock.instanceId().equals(instanceId);
        AbstractWaitCondition waitCondition = acquired ? null : ProcessLockCondition.from(lock);
        queueDao.updateWait(ProcessKey.from(e), waitCondition);
        return LockResult.builder()
                .acquired(acquired)
                .info(lock)
                .build();
    }

    /**
     * Releases the lock.
     */
    @POST
    @ApiOperation("Releases the lock")
    @Path("/{processInstanceId}/unlock/{lockName}")
    @WithTimer
    public void unlock(@PathParam("processInstanceId") UUID instanceId,
                       @PathParam("lockName") String lockName,
                       @QueryParam("scope") @DefaultValue("PROJECT") ProcessLockScope scope) {
        ProcessEntry e = assertProcess(instanceId);

        dao.delete(e.instanceId(), e.orgId(), e.projectId(), scope, lockName);
    }

    private ProcessEntry assertProcess(UUID instanceId) {
        PartialProcessKey processKey = PartialProcessKey.from(instanceId);
        ProcessEntry p = queueDao.get(processKey);
        if (p == null) {
            throw new ConcordApplicationException("Process instance not found", Response.Status.NOT_FOUND);
        }
        if (p.orgId() == null) {
            throw new ConcordApplicationException("Process instance without organization", Response.Status.BAD_REQUEST);
        }
        if (p.projectId() == null) {
            throw new ConcordApplicationException("Process instance without project", Response.Status.BAD_REQUEST);
        }
        return p;
    }
}
