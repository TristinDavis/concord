package com.walmartlabs.concord.server.repository.listeners;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2019 Walmart Inc.
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

import com.walmartlabs.concord.project.ProjectLoader;
import com.walmartlabs.concord.project.model.ProjectDefinition;
import com.walmartlabs.concord.server.org.project.ProjectValidator;
import com.walmartlabs.concord.server.org.project.RepositoryEntry;
import com.walmartlabs.concord.server.org.triggers.TriggerManager;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.file.Path;

@Named
public class TriggerRefreshListener implements RepositoryRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(TriggerRefreshListener.class);

    private final TriggerManager triggerManager;

    @Inject
    public TriggerRefreshListener(TriggerManager triggerManager) {
        this.triggerManager = triggerManager;
    }

    @Override
    public void onRefresh(DSLContext ctx, RepositoryEntry repo, Path repoPath) throws Exception {
        log.info("refresh ['{}'] ->  triggers", repo.getId());

        ProjectDefinition pd = new ProjectLoader().loadProject(repoPath);
        ProjectValidator.validate(pd);

        triggerManager.refresh(repo.getProjectId(), repo.getId(), pd);
    }
}
