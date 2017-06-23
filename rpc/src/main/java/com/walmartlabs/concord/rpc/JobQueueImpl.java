package com.walmartlabs.concord.rpc;

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.walmartlabs.concord.rpc.TJobQueueGrpc.TJobQueueBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class JobQueueImpl implements JobQueue {

    private static final int RETRY_DELAY = 1000;
    private static final int POLL_TIMEOUT = 5000;
    private static final int UPDATE_TIMEOUT = 5000;
    private static final int STATE_UPLOAD_TIMEOUT = 30000;

    private final String agentId;
    private final ManagedChannel channel;

    public JobQueueImpl(String agentId, ManagedChannel channel) {
        this.agentId = agentId;
        this.channel = channel;
    }

    private static Path store(byte[] ab) throws ClientException {
        try {
            // TODO cfg
            Path tmp = Files.createTempFile("payload", ".zip");
            Files.write(tmp, ab);
            return tmp;
        } catch (IOException e) {
            throw new ClientException(e.getMessage(), e);
        }
    }

    private static JobType convert(TJobType t) {
        switch (t) {
            case RUNNER:
                return JobType.RUNNER;
            case JAR:
                return JobType.JAR;
            default:
                throw new IllegalArgumentException("Unsupported job type: " + t);
        }
    }

    private static TJobStatus convert(JobStatus s) {
        switch (s) {
            case RUNNING:
                return TJobStatus.RUNNING;
            case COMPLETED:
                return TJobStatus.COMPLETED;
            case FAILED:
                return TJobStatus.FAILED;
            case CANCELLED:
                return TJobStatus.CANCELLED;
            default:
                throw new IllegalArgumentException("Unsupported job status type: " + s);
        }
    }

    @Override
    public JobEntry take() throws ClientException {
        TJobQueueGrpc.TJobQueueStub stub = TJobQueueGrpc.newStub(channel);

        TJobRequest req = TJobRequest.newBuilder()
                .setAgentId(agentId)
                .build();

        while (true) {
            SettableFuture<JobEntry> f = SettableFuture.create();

            StreamObserver<TJobResponse> observer = new StreamObserver<TJobResponse>() {

                private String instanceId;
                private JobType jobType;
                private Path dst;

                @Override
                public void onNext(TJobResponse value) {
                    try {
                        if (dst == null) {
                            dst = Files.createTempFile("payload", ".zip");
                        }

                        instanceId = value.getInstanceId();
                        jobType = convert(value.getType());
                        byte[] ab = value.getChunk().toByteArray();

                        try (OutputStream out = Files.newOutputStream(dst, StandardOpenOption.APPEND)) {
                            out.write(ab);
                        }
                    } catch (IOException e) {
                        f.setException(e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    f.setException(t);
                }

                @Override
                public void onCompleted() {
                    if (dst == null) {
                        f.set(null);
                        return;
                    }
                    f.set(new JobEntry(instanceId, jobType, dst));
                }
            };

            try {
                stub.withDeadlineAfter(POLL_TIMEOUT, TimeUnit.MILLISECONDS)
                        .poll(req, observer);
            } catch (StatusRuntimeException e) {
                throw new ClientException(e.getMessage(), e);
            }

            try {
                JobEntry entry = f.get();
                if (entry != null) {
                    return entry;
                }

                Thread.sleep(RETRY_DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause == null) {
                    cause = e;
                }
                throw new ClientException(cause.getMessage(), cause);
            }
        }
    }

    @Override
    public void update(String instanceId, JobStatus status) throws ClientException {
        TJobQueueBlockingStub blockingStub = TJobQueueGrpc.newBlockingStub(channel);

        TJobStatusUpdate u = TJobStatusUpdate.newBuilder()
                .setAgentId(agentId)
                .setInstanceId(instanceId)
                .setStatus(convert(status))
                .build();

        try {
            blockingStub.withDeadlineAfter(UPDATE_TIMEOUT, TimeUnit.MILLISECONDS)
                    .updateStatus(u);
        } catch (StatusRuntimeException e) {
            throw new ClientException(e.getMessage(), e);
        }
    }

    @Override
    public void appendLog(String instanceId, byte[] data) throws ClientException {
        TJobQueueBlockingStub blockingStub = TJobQueueGrpc.newBlockingStub(channel);

        TJobLogEntry l = TJobLogEntry.newBuilder()
                .setInstanceId(instanceId)
                .setData(ByteString.copyFrom(data))
                .build();

        try {
            blockingStub.withDeadlineAfter(UPDATE_TIMEOUT, TimeUnit.MILLISECONDS)
                    .appendLog(l);
        } catch (StatusRuntimeException e) {
            throw new ClientException(e.getMessage(), e);
        }
    }

    @Override
    public void uploadAttachments(String instanceId, Path src) throws ClientException {
        TJobQueueGrpc.TJobQueueBlockingStub blockingStub = TJobQueueGrpc.newBlockingStub(channel);

        try {
            byte[] ab = Files.readAllBytes(src);
            TAttachments a = TAttachments.newBuilder()
                    .setInstanceId(instanceId)
                    .setData(ByteString.copyFrom(ab))
                    .build();

            try {
                blockingStub.withDeadlineAfter(STATE_UPLOAD_TIMEOUT, TimeUnit.MILLISECONDS)
                        .uploadAttachments(a);
            } catch (StatusRuntimeException e) {
                throw new ClientException(e.getMessage(), e);
            }
        } catch (IOException e) {
            throw new ClientException(e.getMessage(), e);
        }
    }
}