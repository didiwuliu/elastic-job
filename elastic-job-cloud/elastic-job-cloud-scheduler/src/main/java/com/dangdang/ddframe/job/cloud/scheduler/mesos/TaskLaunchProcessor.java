/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.cloud.scheduler.mesos;

import com.dangdang.ddframe.job.cloud.scheduler.boot.env.BootstrapEnvironment;
import com.dangdang.ddframe.job.cloud.scheduler.config.CloudJobConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.context.TaskContext;
import com.dangdang.ddframe.job.executor.ShardingContexts;
import com.dangdang.ddframe.job.util.BlockUtils;
import com.dangdang.ddframe.job.util.config.ShardingItemParameters;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.netflix.fenzo.TaskAssignmentResult;
import com.netflix.fenzo.TaskScheduler;
import com.netflix.fenzo.VMAssignmentResult;
import com.netflix.fenzo.VirtualMachineLease;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.SchedulerDriver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务启动处理器.
 *
 * @author zhangliang
 */
@RequiredArgsConstructor
@Slf4j
public final class TaskLaunchProcessor implements Runnable {
    
    private static volatile boolean shutdown;
    
    private final LeasesQueue leasesQueue;
    
    private final SchedulerDriver schedulerDriver;
    
    private final TaskScheduler taskScheduler;
    
    private final FacadeService facadeService;
    
    /**
     * 线程关闭.
     */
    public static void shutdown() {
        shutdown = true;
    }
    
    @Override
    public void run() {
        while (!shutdown) {
            LaunchingTasks launchingTasks = new LaunchingTasks(facadeService.getEligibleJobContext());
            Collection<VMAssignmentResult> vmAssignmentResults = taskScheduler.scheduleOnce(launchingTasks.getPendingTasks(), leasesQueue.drainTo()).getResultMap().values();
            for (VMAssignmentResult each: vmAssignmentResults) {
                List<VirtualMachineLease> leasesUsed = each.getLeasesUsed();
                List<Protos.TaskInfo> taskInfoList = new ArrayList<>(each.getTasksAssigned().size() * 10);
                taskInfoList.addAll(getTaskInfoList(launchingTasks.getIntegrityViolationJobs(vmAssignmentResults), each, leasesUsed.get(0).hostname(), leasesUsed.get(0).getOffer().getSlaveId()));
                for (Protos.TaskInfo taskInfo : taskInfoList) {
                    facadeService.addRunning(TaskContext.from(taskInfo.getTaskId().getValue()));
                }
                facadeService.removeLaunchTasksFromQueue(Lists.transform(taskInfoList, new Function<TaskInfo, TaskContext>() {
                    
                    @Override
                    public TaskContext apply(final Protos.TaskInfo input) {
                        return TaskContext.from(input.getTaskId().getValue());
                    }
                }));
                schedulerDriver.launchTasks(getOfferIDs(leasesUsed), taskInfoList);
            }
            BlockUtils.waitingShortTime();
        }
    }
    
    private List<Protos.TaskInfo> getTaskInfoList(final Collection<String> integrityViolationJobs, final VMAssignmentResult vmAssignmentResult, final String hostname, final Protos.SlaveID slaveId) {
        List<Protos.TaskInfo> result = new ArrayList<>(vmAssignmentResult.getTasksAssigned().size());
        for (TaskAssignmentResult each: vmAssignmentResult.getTasksAssigned()) {
            TaskContext taskContext = TaskContext.from(each.getTaskId());
            if (!integrityViolationJobs.contains(taskContext.getMetaInfo().getJobName()) && !facadeService.isRunning(taskContext)) {
                Protos.TaskInfo taskInfo = getTaskInfo(slaveId, each);
                if (null != taskInfo) {
                    result.add(getTaskInfo(slaveId, each));
                    facadeService.addMapping(taskInfo.getTaskId().getValue(), hostname);
                    taskScheduler.getTaskAssigner().call(each.getRequest(), hostname);
                }
            }
        }
        return result;
    }
    
    private Protos.TaskInfo getTaskInfo(final Protos.SlaveID slaveID, final TaskAssignmentResult taskAssignmentResult) {
        TaskContext originalTaskContext = TaskContext.from(taskAssignmentResult.getTaskId());
        int shardingItem = originalTaskContext.getMetaInfo().getShardingItem();
        TaskContext taskContext = new TaskContext(originalTaskContext.getMetaInfo().getJobName(), shardingItem, originalTaskContext.getType(), slaveID.getValue());
        Optional<CloudJobConfiguration> jobConfigOptional = facadeService.load(taskContext.getMetaInfo().getJobName());
        if (!jobConfigOptional.isPresent()) {
            return null;
        }
        CloudJobConfiguration jobConfig = jobConfigOptional.get();
        Map<Integer, String> shardingItemParameters = new ShardingItemParameters(jobConfig.getTypeConfig().getCoreConfig().getShardingItemParameters()).getMap();
        Map<Integer, String> assignedShardingItemParameters = new HashMap<>(1, 1);
        assignedShardingItemParameters.put(shardingItem, shardingItemParameters.containsKey(shardingItem) ? shardingItemParameters.get(shardingItem) : "");
        ShardingContexts shardingContexts = new ShardingContexts(
                jobConfig.getJobName(), jobConfig.getTypeConfig().getCoreConfig().getShardingTotalCount(), jobConfig.getTypeConfig().getCoreConfig().getJobParameter(), assignedShardingItemParameters);
        Protos.CommandInfo.URI uri = Protos.CommandInfo.URI.newBuilder().setValue(jobConfig.getAppURL()).setExtract(true)
                .setCache(BootstrapEnvironment.getInstance().getFrameworkConfiguration().isAppCacheEnable()).build();
        Protos.CommandInfo command = Protos.CommandInfo.newBuilder().addUris(uri).setShell(true).setValue(jobConfig.getBootstrapScript()).build();
        Protos.ExecutorInfo executorInfo = 
                Protos.ExecutorInfo.newBuilder().setExecutorId(Protos.ExecutorID.newBuilder().setValue(taskContext.getExecutorId(jobConfig.getAppURL()))).setCommand(command).build();
        return Protos.TaskInfo.newBuilder()
                .setTaskId(Protos.TaskID.newBuilder().setValue(taskContext.getId()).build())
                .setName(taskContext.getTaskName())
                .setSlaveId(slaveID)
                .addResources(buildResource("cpus", jobConfig.getCpuCount()))
                .addResources(buildResource("mem", jobConfig.getMemoryMB()))
                .setExecutor(executorInfo)
                .setData(ByteString.copyFrom(new TaskInfoData(shardingContexts, jobConfig).serialize()))
                .build();
    }
    
    private Protos.Resource.Builder buildResource(final String type, final double resourceValue) {
        return Protos.Resource.newBuilder().setName(type).setType(Protos.Value.Type.SCALAR).setScalar(Protos.Value.Scalar.newBuilder().setValue(resourceValue));
    }
    
    private List<Protos.OfferID> getOfferIDs(final List<VirtualMachineLease> leasesUsed) {
        List<Protos.OfferID> result = new ArrayList<>();
        for (VirtualMachineLease virtualMachineLease: leasesUsed) {
            result.add(virtualMachineLease.getOffer().getId());
        }
        return result;
    }
}
