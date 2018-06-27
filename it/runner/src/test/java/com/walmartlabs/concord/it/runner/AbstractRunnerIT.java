package com.walmartlabs.concord.it.runner;

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


import com.walmartlabs.concord.common.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractRunnerIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractRunnerIT.class);

    private static final String JAVA_CMD = System.getProperty("java.home") + "/bin/java";

    protected static Process exec(String instanceId, Path workDir) throws IOException {
        return exec(instanceId, workDir, false);
    }

    protected static Process exec(String instanceId, Path workDir, boolean waitForDebugger) throws IOException {
        // TODO constants
        Path idPath = workDir.resolve("_instanceId");
        Files.write(idPath, instanceId.getBytes());

        List<String> cmd = new ArrayList<>();
        cmd.add(JAVA_CMD);
        if (waitForDebugger) {
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005");
        }
        cmd.add("-cp");
        cmd.add("lib/*:" + getRunnerPath());
        cmd.add("com.walmartlabs.concord.runner.Main");

        log.info("Starting: " + String.join(" ", cmd));

        ProcessBuilder b = new ProcessBuilder()
                .command(cmd.toArray(new String[0]))
                .redirectErrorStream(true)
                .directory(workDir.toFile());

        return b.start();
    }

    protected static byte[] readLog(Process proc) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(proc.getInputStream(), baos);
        return baos.toByteArray();
    }

    private static String getRunnerPath() {
        return System.getenv("IT_RUNNER_PATH");
    }
}
