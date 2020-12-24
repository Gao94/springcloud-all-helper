package com.chippy.elasticjob.support.api;

import cn.hutool.core.lang.Assert;
import cn.hutool.json.JSONUtil;
import com.chippy.core.common.utils.CollectionsUtils;
import com.chippy.elasticjob.exception.DuplicateCreationException;
import com.chippy.elasticjob.support.domain.JobInfo;
import com.chippy.elasticjob.support.enums.JobStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.elasticjob.api.ElasticJob;
import org.apache.shardingsphere.elasticjob.api.JobConfiguration;
import org.apache.shardingsphere.elasticjob.api.listener.ElasticJobListener;
import org.apache.shardingsphere.elasticjob.infra.pojo.JobConfigurationPOJO;
import org.apache.shardingsphere.elasticjob.lite.api.bootstrap.impl.ScheduleJobBootstrap;
import org.apache.shardingsphere.elasticjob.lite.lifecycle.api.JobConfigurationAPI;
import org.apache.shardingsphere.elasticjob.lite.lifecycle.api.JobOperateAPI;
import org.apache.shardingsphere.elasticjob.lite.lifecycle.api.JobStatisticsAPI;
import org.apache.shardingsphere.elasticjob.lite.lifecycle.domain.JobBriefInfo;
import org.apache.shardingsphere.elasticjob.reg.zookeeper.ZookeeperRegistryCenter;
import org.apache.shardingsphere.elasticjob.tracing.api.TracingConfiguration;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 任务状态跟踪记录抽象实现基础功能
 * 一般来说和{@link AbstractTraceJob}是想配合着用的
 *
 * @author: chippy
 * @datetime 2020-11-11 18:14
 */
@Slf4j
public abstract class AbstractTraceJobHandler implements TraceJobHandler {

    @Resource
    protected ZookeeperRegistryCenter registryCenter;

    @Resource
    protected ElasticJobListener elasticJobListener;

    @Resource
    protected JobConfigurationAPI jobConfigurationAPI;

    @Resource
    protected JobStatisticsAPI jobStatisticsAPI;

    @Resource
    protected JobOperateAPI jobOperateAPI;

    @Resource
    private TracingConfiguration tracingConfiguration;

    @Resource
    private TraceJobOperationService traceJobOperationService;

    public abstract ElasticJob getJob();

    public abstract String getErrorMessageFormat();

    @Override
    public void createJob(JobInfo jobInfo) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("创建定时任务:[" + JSONUtil.toJsonStr(jobInfo) + "]");
            }
            final List<JobInfo> jobInfos =
                traceJobOperationService.byOriginalJobName(jobInfo.getOriginalJobName(), JobStatusEnum.READY);
            if (CollectionsUtils.isNotEmpty(jobInfos)) {
                throw new DuplicateCreationException("任务信息[" + jobInfo.getOriginalJobName() + "]已存在");
            }
            this.doCreateJob(jobInfo);
            jobInfo.setInvokeServiceClass(this.getClass().getName());
            traceJobOperationService.insert(jobInfo);
        } catch (Exception e) {
            log.error(String.format(getErrorMessageFormat() + "-%s", e.getMessage()));
            throw e;
        }
    }

    private void doCreateJob(JobInfo jobInfo) {
        JobConfiguration jobConfig =
            JobConfiguration.newBuilder(jobInfo.getJobName(), jobInfo.getShardingTotalCount()).cron(jobInfo.getCron())
                .jobParameter(jobInfo.getJobParameter()).failover(Boolean.TRUE)
                .shardingItemParameters(jobInfo.getShardingParameter()).build();
        new ScheduleJobBootstrap(registryCenter, this.getJob(), jobConfig, tracingConfiguration, elasticJobListener)
            .schedule();
    }

    @Override
    public void removeJob(String originalJobName) {
        Assert.notNull(originalJobName, "需要移除的定时任务名称不能为空");
        if (log.isDebugEnabled()) {
            log.debug("移除定时任务:[" + originalJobName + "]");
        }
        final List<JobInfo> jobInfos = traceJobOperationService.byOriginalJobName(originalJobName, JobStatusEnum.READY);
        if (CollectionsUtils.isEmpty(jobInfos)) {
            log.debug("需要删除的任务信息已不存在");
            return;
        }
        final JobInfo existsJobInfo = jobInfos.get(0);
        this.doRemove(existsJobInfo);
    }

    private void doRemove(JobInfo jobInfo) {
        String jobName = jobInfo.getJobName();
        jobOperateAPI.disable(jobName, null);
        jobOperateAPI.remove(jobName, null);
        jobInfo.setStatus(JobStatusEnum.OVER.toString());
        traceJobOperationService.update(jobInfo);
    }

    @Override
    public void updateJob(JobInfo jobInfo) {
        Assert.notNull(jobInfo, "需要更新的定时任务不能为空");
        log.debug("更新定时任务:[" + JSONUtil.toJsonStr(jobInfo) + "]");
        try {
            final List<JobInfo> jobInfos =
                traceJobOperationService.byOriginalJobName(jobInfo.getOriginalJobName(), JobStatusEnum.READY);
            if (CollectionsUtils.isEmpty(jobInfos)) {
                this.createJob(jobInfo);
                return;
            }

            /*
               为了避免任务名称变更，出现两条同样的逻辑但是不同时间点执行的任务
               列入：A任务需要进修修改， 查询任务状态后发现任务名称变更了查询不存在则新增任务任务状态B
               此时出现A, B两条任务均存在任务状态数据中，此时服务器宕机重启则会导致A, B都将被启用
               ---
               故此此处不进行任何修改操作，用删除插入两个动作进行弥补
             */
            final JobInfo existsJobInfo = jobInfos.get(0);
            this.doRemove(existsJobInfo);
            this.createJob(jobInfo);
        } catch (Exception e) {
            String exceptionMessage = "更新的定时任务:[" + jobInfo.getJobName() + "]信息已不存在 -> [" + e.getMessage() + "]";
            log.error(exceptionMessage);
            throw e;
        }
    }

    @Override
    public List<JobConfigurationPOJO> getJob(String originalJobName) {
        try {
            log.debug("获取定时任务:[" + originalJobName + "]");
            final List<JobInfo> jobInfos = traceJobOperationService.byOriginalJobName(originalJobName, null);
            return CollectionsUtils.isEmpty(jobInfos) ? Collections.emptyList() : jobInfos.stream()
                .map(completeJobInfo -> jobConfigurationAPI.getJobConfiguration(completeJobInfo.getJobName()))
                .filter(Objects::nonNull).collect(Collectors.toList());
        } catch (NullPointerException e) {
            log.error("要活动的定时任务:[" + originalJobName + "]不存在 -> [" + e.getMessage() + "]");
            log.error("具体异常->", e);
            return null;
        }
    }

    @Override
    public List<JobBriefInfo> getJobBriefInfo(String originalJobName) {
        log.debug("获取简明定时任务集合信息:[" + originalJobName + "]");
        if (Objects.isNull(originalJobName)) {
            return null;
        }
        final List<JobInfo> jobInfos = traceJobOperationService.byOriginalJobName(originalJobName, null);
        return CollectionsUtils.isEmpty(jobInfos) ? Collections.emptyList() :
            jobInfos.stream().map(jobInfo -> jobStatisticsAPI.getJobBriefInfo(jobInfo.getJobName()))
                .filter(Objects::nonNull).collect(Collectors.toList());
    }

}
