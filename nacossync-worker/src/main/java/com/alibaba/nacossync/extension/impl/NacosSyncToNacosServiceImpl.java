/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.alibaba.nacossync.extension.impl;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacos.common.utils.ConcurrentHashSet;
import com.alibaba.nacossync.cache.SkyWalkerCacheServices;
import com.alibaba.nacossync.constant.ClusterTypeEnum;
import com.alibaba.nacossync.constant.MetricsStatisticsType;
import com.alibaba.nacossync.constant.SkyWalkerConstants;
import com.alibaba.nacossync.dao.ClusterAccessService;
import com.alibaba.nacossync.extension.SyncService;
import com.alibaba.nacossync.extension.annotation.NacosSyncService;
import com.alibaba.nacossync.extension.holder.NacosServerHolder;
import com.alibaba.nacossync.monitor.MetricsManager;
import com.alibaba.nacossync.pojo.model.TaskDO;
import com.alibaba.nacossync.template.processor.TaskUpdateProcessor;
import com.alibaba.nacossync.timer.FastSyncHelper;
import com.alibaba.nacossync.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.alibaba.nacossync.util.NacosUtils.getGroupNameOrDefault;

/**
 * @author yangyshdan
 * @version $Id: ConfigServerSyncManagerService.java, v 0.1 2018-11-12 下午5:17 NacosSync Exp $$
 */

@Slf4j
@NacosSyncService(sourceCluster = ClusterTypeEnum.NACOS, destinationCluster = ClusterTypeEnum.NACOS)
public class NacosSyncToNacosServiceImpl implements SyncService, InitializingBean {
    
    private Map<String, EventListener> listenerMap = new ConcurrentHashMap<>();
    
    private final Map<String, Integer> syncTaskTap = new ConcurrentHashMap<>();
    
    @Autowired
    private MetricsManager metricsManager;
    
    @Autowired
    private SkyWalkerCacheServices skyWalkerCacheServices;
    
    @Autowired
    private NacosServerHolder nacosServerHolder;
    
    private ConcurrentHashMap<String, TaskDO> allSyncTaskMap = new ConcurrentHashMap<>();
    
    @Autowired
    private ClusterAccessService clusterAccessService;
    
    public static Map<String, Set<NamingService>> serviceClient = new ConcurrentHashMap<>();
    
    @Autowired
    private FastSyncHelper fastSyncHelper;
    
    @Autowired
    private TaskUpdateProcessor taskUpdateProcessor;
    
    /**
     * 因为网络故障等原因，nacos sync的同步任务会失败，导致目标集群注册中心缺少同步实例， 为避免目标集群注册中心长时间缺少同步实例，每隔5分钟启动一个兜底工作线程执行一遍全部的同步任务。
     */
    
    
    @Override
    public void afterPropertiesSet() {
        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("com.alibaba.nacossync.basic.synctask");
            return t;
        });
        
        executorService.scheduleWithFixedDelay(() -> {
            if (allSyncTaskMap.size() == 0) {
                return;
            }
            
            try {
                Collection<TaskDO> taskCollections = allSyncTaskMap.values();
                List<TaskDO> taskDOList = new ArrayList<>(taskCollections);
                
                if (CollectionUtils.isNotEmpty(taskDOList)) {
                    fastSyncHelper.syncWithThread(taskDOList, this::timeSync);
                }
                
            } catch (Throwable e) {
                log.warn("basic synctask thread error", e);
            }
        }, 0, 300, TimeUnit.SECONDS);
    }
    
    @Override
    public boolean delete(TaskDO taskDO) {
        try {
            String taskId = taskDO.getTaskId();
            NamingService sourceNamingService = nacosServerHolder.getSourceNamingService(taskId,
                    taskDO.getSourceClusterId());
            String groupName = getGroupNameOrDefault(taskDO.getGroupName());
            
            if ("ALL".equals(taskDO.getServiceName())) {
                String operationId = taskUpdateProcessor.getTaskIdAndOperationIdMap(taskId);
                if (!StringUtils.isEmpty(operationId)) {
                    allSyncTaskMap.remove(operationId);
                }
                
                //处理group级别的服务任务删除
                ListView<String> servicesOfServer = sourceNamingService.getServicesOfServer(0, Integer.MAX_VALUE, groupName);
                List<String> serviceNames = servicesOfServer.getData();
                for (String serviceName : serviceNames) {
                    String operationKey = taskId + serviceName;
                    skyWalkerCacheServices.removeFinishedTask(operationKey);
                    allSyncTaskMap.remove(operationKey);
                    
                    sourceNamingService.unsubscribe(serviceName, groupName,
                            listenerMap.remove(taskId + serviceName));
                    
                    List<Instance> sourceInstances = sourceNamingService.getAllInstances(serviceName,
                            groupName, new ArrayList<>(), false);
                    List<Instance> needDeregisterInstances = new ArrayList<>();
                    for (Instance instance : sourceInstances) {
                        if (needSync(instance.getMetadata())) {
                            removeUnwantedAttrsForNacosRedo(instance);
                            log.debug("需要反注册的实例: {}", instance);
                            needDeregisterInstances.add(instance);
                        }
                    }
                    if (CollectionUtils.isNotEmpty(needDeregisterInstances)) {
                        NamingService destNamingService = popNamingService(taskDO);
                        doDeregisterInstance(taskDO, destNamingService, serviceName, groupName, needDeregisterInstances);
                    }
                }
            } else {
                //处理服务级别的任务删除
                String operationId = taskUpdateProcessor.getTaskIdAndOperationIdMap(taskId);
                if (StringUtils.isEmpty(operationId)) {
                    log.warn("operationId is null data synchronization is not currently performed.{}", operationId);
                    return false;
                }
                
                String serviceName = taskDO.getServiceName();
                sourceNamingService.unsubscribe(serviceName, groupName,
                        listenerMap.remove(operationId));
                List<Instance> sourceInstances = sourceNamingService.getAllInstances(serviceName,
                        groupName, new ArrayList<>(), false);
                
                List<Instance> needDeregisterInstances = new ArrayList<>();
                for (Instance instance : sourceInstances) {
                    if (needSync(instance.getMetadata())) {
                        removeUnwantedAttrsForNacosRedo(instance);
                        log.debug("需要反注册的实例: {}", instance);
                        needDeregisterInstances.add(instance);
                    }
                }
                if (CollectionUtils.isNotEmpty(needDeregisterInstances)) {
                    NamingService destNamingService = popNamingService(taskDO);
                    doDeregisterInstance(taskDO, destNamingService, serviceName, groupName, needDeregisterInstances);
                }
                // 移除任务
                skyWalkerCacheServices.removeFinishedTask(operationId);
                // 移除所有需要同步的Task
                allSyncTaskMap.remove(operationId);
            }
        } catch (Exception e) {
            log.error("delete task from nacos to nacos was failed, operationalId:{}", taskDO.getOperationId(), e);
            metricsManager.recordError(MetricsStatisticsType.DELETE_ERROR);
            return false;
        }
        return true;
    }
    
    @Override
    public boolean sync(TaskDO taskDO, Integer index) {
        log.info("线程 {} 开始同步 {} ", Thread.currentThread().getId(), System.currentTimeMillis());
        String operationId = taskDO.getOperationId();
        try {
            NamingService sourceNamingService = nacosServerHolder.getSourceNamingService(taskDO.getTaskId(),
                    taskDO.getSourceClusterId());
            NamingService destNamingService = getDestNamingService(taskDO, index);
            allSyncTaskMap.put(operationId, taskDO);
            //防止暂停同步任务后,重新同步/或删除任务以后新建任务不会再接收到新的事件导致不能同步,所以每次订阅事件之前,先全量同步一次任务
            long startTime = System.currentTimeMillis();
            doSync(operationId, taskDO, sourceNamingService, destNamingService);
            log.info("同步一个服务注册耗时:{} ms", System.currentTimeMillis() - startTime);
            this.listenerMap.putIfAbsent(operationId, event -> {
                if (event instanceof NamingEvent) {
                    NamingEvent namingEvent = (NamingEvent) event;
                    log.info("监听到服务{}信息改变, taskId：{}，实例数:{}，发起同步", namingEvent.getServiceName(),
                            operationId, namingEvent.getInstances() == null ? null : namingEvent.getInstances().size());
                    try {
                        doSync(operationId, taskDO, sourceNamingService, destNamingService);
                        log.info("监听到服务{}同步结束", namingEvent.getServiceName());
                    } catch (Exception e) {
                        log.error("event process fail, operationId:{}", operationId, e);
                        metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
                    }
                }
            });
            sourceNamingService.subscribe(taskDO.getServiceName(), getGroupNameOrDefault(taskDO.getGroupName()),
                    listenerMap.get(operationId));
        } catch (Exception e) {
            log.error("sync task from nacos to nacos was failed, operationId:{}", operationId, e);
            metricsManager.recordError(MetricsStatisticsType.SYNC_ERROR);
            return false;
        }
        return true;
    }
    
    /**
     * basic sync
     *
     * @param taskDO
     */
    public void timeSync(TaskDO taskDO) {
        log.debug("线程{}开始同步{}", Thread.currentThread().getId(), System.currentTimeMillis());
        String operationId = taskDO.getOperationId();
        try {
            NamingService sourceNamingService = nacosServerHolder.getSourceNamingService(taskDO.getTaskId(),
                    taskDO.getSourceClusterId());
            //获取目标集群client
            NamingService destNamingService = popNamingService(taskDO);
            long startTime = System.currentTimeMillis();
            doSync(operationId, taskDO, sourceNamingService, destNamingService);
            log.info("同步一个服务注册耗时:{} ms", System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private NamingService getDestNamingService(TaskDO taskDO, Integer index) {
        String key = taskDO.getSourceClusterId() + ":" + taskDO.getDestClusterId() + ":" + index;
        return nacosServerHolder.get(key);
    }
    
    private void doSync(String taskId, TaskDO taskDO, NamingService sourceNamingService,
            NamingService destNamingService) throws NacosException {
        if (syncTaskTap.putIfAbsent(taskId, 1) != null) {
            log.info("任务Id:{}上一个同步任务尚未结束", taskId);
            return;
        }
        //记录目标集群的Client
        recordNamingService(taskDO, destNamingService);
        try {
            
            String serviceName = taskDO.getServiceName();
            String groupName = getGroupNameOrDefault(taskDO.getGroupName());
            List<Instance> sourceInstances = sourceNamingService.getAllInstances(serviceName,
                    groupName, new ArrayList<>(), true);
            
            String sourceClusterId = taskDO.getSourceClusterId();
            int level = clusterAccessService.findClusterLevel(sourceClusterId);
            if (CollectionUtils.isNotEmpty(sourceInstances) && sourceInstances.get(0).isEphemeral()) {
                //处理临时实例批量同步：nacos2批量注册接口采用全量更新方式，实例列表需包含直接注册到源集群的全量实例
                handlerEphemeralInstance(taskDO, destNamingService, serviceName, groupName, sourceInstances, level);
            } else if (CollectionUtils.isEmpty(sourceInstances)) {
                //如果当前源集群是空的 ，那么注销目标集群中来自当前源集群的同步实例
                log.debug("serviceName {} need sync instance num from cluster {} is null", serviceName, sourceClusterId);
                processDeRegisterInstances(taskDO, destNamingService, serviceName, groupName);
            } else {
                //处理持久化实例的批量数据同步
                handlerPersistenceInstance(taskDO, destNamingService, serviceName, groupName, sourceInstances, level);
            }
        } finally {
            syncTaskTap.remove(taskId);
        }
    }
    
    /**
     * 通过nacos2批量注册接口全量同步源集群需要同步到目标集群指定service的所有临时实例
     */
    private void handlerEphemeralInstance(TaskDO taskDO, NamingService destNamingService, String serviceName, String groupName,
            List<Instance> sourceInstances, int level) throws NacosException {
        
        //构建源集群指定service需要同步到目标集群的全量实例列表
        List<Instance> needRegisterInstances = new ArrayList<>();
        String destClusterId = taskDO.getDestClusterId();
        String sourceClusterId = taskDO.getSourceClusterId();
        String syncSourceKey = skyWalkerCacheServices.getClusterType(sourceClusterId).getCode();
        String version = taskDO.getVersion();
        for (Instance instance : sourceInstances) {
            if (needSync(instance.getMetadata(), level, destClusterId)) {
                Instance syncInstance = buildSyncInstance(instance, destClusterId, sourceClusterId, syncSourceKey, version);
                log.debug("需要从源集群同步到目标集群的临时实例：{}", syncInstance);
                needRegisterInstances.add(instance);
            }
        }

        if (CollectionUtils.isNotEmpty(needRegisterInstances)) {
            //批量注册
            log.debug("将源集群指定service的临时实例全量同步到目标集群: {}", taskDO);
            destNamingService.batchRegisterInstance(serviceName, groupName, needRegisterInstances);
        } else {
            //注销目标集群指定service来自当前源集群同步的所有实例
            processDeRegisterInstances(taskDO, destNamingService, serviceName, groupName);
        }
    }
    
    /**
     * 持久实例只需要逐个注册新注册的实例，逐个反注册已注销的实例
     */
    private void handlerPersistenceInstance(TaskDO taskDO, NamingService destNamingService, String serviceName, String groupName,
            List<Instance> sourceInstances, int level) throws NacosException {
        
        // 源集群指定service的全量实例
        List<Instance> needRegisterInstances = new ArrayList<>();
        String destClusterId = taskDO.getDestClusterId();
        for (Instance instance : sourceInstances) {
            if (needSync(instance.getMetadata(), level, destClusterId)) {
                needRegisterInstances.add(instance);
            }
        }
        
        // 获取目标集群指定service的全量实例 
        List<Instance> destAllInstances = destNamingService.getAllInstances(serviceName,
                groupName, new ArrayList<>(), true);
        
        // 获取目标集群指定service中来自当前源集群同步的实例
        String sourceClusterId = taskDO.getSourceClusterId();
        List<Instance> destHasSyncInstances = destAllInstances.stream()
                .filter(instance -> hasSync(instance, sourceClusterId)).collect(Collectors.toList());
        
        // 获取当前源集群指定service的新增实例（尚未注册到目标集群的实例）、目标集群和源集群同时存在指定service的实例
        List<Instance> newInstances = new ArrayList<>(needRegisterInstances);        
        List<Instance> bothExistedInstances = instanceRemove(destHasSyncInstances, newInstances);
        
        // 逐个注册源集群新增的实例到目标集群
        String syncSourceKey = skyWalkerCacheServices.getClusterType(sourceClusterId).getCode();
        String version = taskDO.getVersion();
        for (Instance newInstance : newInstances) {
            Instance syncInstance = buildSyncInstance(newInstance, destClusterId, sourceClusterId, syncSourceKey, version);
            log.debug("从源集群同步到目标集群的持久实例：{}", syncInstance);
            destNamingService.registerInstance(serviceName, groupName, syncInstance);
        }
        
        // 获取目标集群来自当前源集群同步的指定service实例中需要注销的实例（实例在源集群中已注销）
        destHasSyncInstances.removeAll(bothExistedInstances);
        
        if (CollectionUtils.isNotEmpty(destHasSyncInstances)) {
            log.info("taskid：{}，服务 {} 发生反注册，执行数量 {} ", taskDO.getTaskId(), serviceName, destHasSyncInstances.size());
            for (Instance needDeregisterInstance : destHasSyncInstances) {
                destNamingService.deregisterInstance(serviceName, groupName, needDeregisterInstance);
            }
        }        
    }
        
    public static boolean instanceEquals(Instance ins1, Instance ins2) {
        return (ins1.getIp().equals(ins2.getIp())) && (ins1.getPort() == ins2.getPort()) && (ins1.getWeight()
                == ins2.getWeight()) && (ins1.isHealthy() == ins2.isHealthy()) && (ins1.isEphemeral()
                == ins2.isEphemeral()) && (ins1.getClusterName().equals(ins2.getClusterName()))
                && (ins1.getServiceName().equals(ins2.getServiceName()));
    }
    
    /**
     * 获取新增实例：从源集群指定service需要同步到目标集群的全量实例列表中排除目标集群中已存在的来自当前源集群同步的实例，
     * 返回目标集群和源集群均存在的实例
     */
    private List<Instance> instanceRemove(List<Instance> destHasSyncInstances, List<Instance> newInstances) {
        List<Instance> bothExistedInstances = new ArrayList<>();
        for (Instance destHasSyncInstance : destHasSyncInstances) {
            for (Instance newInstance : newInstances) {
                // fix bug: 目标集群同步实例元数据比源集群实例元数据多了SOURCE_CLUSTERID_KEY等数据，不能用Instance#equals比较
                if (instanceEquals(destHasSyncInstance, newInstance)) {
                    //如果目标集群已经存在了源集群同步过来的实例，就不需要同步了
                    bothExistedInstances.add(newInstance);
                }
            }
        }
        // eg:A Cluster 已经同步到 B Cluster的实例数据，就不需要再重复同步过来了
        newInstances.removeAll(bothExistedInstances);
        return bothExistedInstances;
    }
    
    private boolean hasSync(Instance instance, String sourceClusterId) {
        if (instance.getMetadata() != null) {
            String sourceClusterKey = instance.getMetadata().get(SkyWalkerConstants.SOURCE_CLUSTERID_KEY);
            return sourceClusterKey != null && sourceClusterKey.equals(sourceClusterId);
        }
        return false;
    }
    
    /**
     * 当源集群需要同步的实例个数为0时,目标集群如果还有源集群同步的实例，执行反注册
     *
     * @param taskDO
     * @param destNamingService
     * @throws NacosException
     */
    private void processDeRegisterInstances(TaskDO taskDO, NamingService destNamingService,
            String serviceName, String groupName) throws NacosException {
        List<Instance> destInstances = destNamingService.getAllInstances(serviceName,
                groupName, new ArrayList<>(), false);
        // 如果目标集群中的数据实例也为空了，则无需操作
        if (CollectionUtils.isEmpty(destInstances)) {
            return;
        }
        // 过滤出目标集群中来自当前源集群的同步实例中需要进行注销的实例
        List<Instance> needDeregisterInstances = deRegisterFilter(destInstances, taskDO.getSourceClusterId());
        // 反注册注销实例
        doDeregisterInstance(taskDO, destNamingService, serviceName, groupName, needDeregisterInstances);
    }
    
    private List<Instance> deRegisterFilter(List<Instance> destInstances, String sourceClusterId) {
        List<Instance> needDeregisterInstances = new ArrayList<>();
        for (Instance destInstance : destInstances) {
            Map<String, String> metadata = destInstance.getMetadata();
            String destSourceClusterId = metadata.get(SkyWalkerConstants.SOURCE_CLUSTERID_KEY);
            if (needDeregister(destSourceClusterId, sourceClusterId)) {
                removeUnwantedAttrsForNacosRedo(destInstance);
                log.debug("需要反注册的实例: {}", destInstance);
                needDeregisterInstances.add(destInstance);
            }
        }
        // fix bug：在方法里对引用对象destInstances赋值并不能改变方法外使用的destInstances
        return needDeregisterInstances;
    }
    
    private void doDeregisterInstance(TaskDO taskDO, NamingService destNamingService, String serviceName, String groupName,
            List<Instance> instances) throws NacosException {
        if (CollectionUtils.isNotEmpty(instances)) {
            if (instances.get(0).isEphemeral()) {
                log.debug("批量反注册来自源集群的同步实例: {}", taskDO);
                destNamingService.batchDeregisterInstance(serviceName, groupName, instances);               
            } else {
                // 目前nacos2提供的批量反注册接口不支持持久实例，因此只能逐个反注册
                for (Instance instance : instances) {
                    log.debug("逐个反注册来自源集群的同步实例: {}", instance);
                    destNamingService.deregisterInstance(serviceName, groupName, instance);
                }
            }
        }
    }
    
    private void removeUnwantedAttrsForNacosRedo(Instance instance) {
        //清空查询实例返回的instanceId以保证nacos批量注册接口正常匹配redo缓存（nacos-sync调用批量注册接口时未设置instanceId，redo缓存实例对象的instanceId属性为null）
        instance.setInstanceId(null);
        //清空查询实例返回的serviceName（nacos2.x查询实例返回的serviceName包含组名，nacos2.x批量接口参数检验规则要求服务名不能包含组名）
        instance.setServiceName(null);
    }
    
    private boolean needDeregister(String destSourceClusterId, String sourceClusterId) {
        if (!StringUtils.isEmpty(destSourceClusterId)) {
            return destSourceClusterId.equals(sourceClusterId);
        }
        return false;
    }
    
    private boolean needSync(Map<String, String> sourceMetaData, int level, String destClusterId) {
        //普通集群（默认）
        if (level == 0) {
            return SyncService.super.needSync(sourceMetaData);
        }
        //中心集群，只要不是目标集群传过来的实例，都需要同步（扩展功能）
        if (!destClusterId.equals(sourceMetaData.get(SkyWalkerConstants.SOURCE_CLUSTERID_KEY))) {
            return true;
        }
        return false;
    }
    
    private void recordNamingService(TaskDO taskDO, NamingService destNamingService) {
        String key = buildClientKey(taskDO);
        serviceClient.computeIfAbsent(key, clientKey -> {
            Set<NamingService> hashSet = new ConcurrentHashSet<>();
            hashSet.add(destNamingService);
            return hashSet;
        });
    }
    
    public NamingService popNamingService(TaskDO taskDO) {
        String key = buildClientKey(taskDO);
        Set<NamingService> namingServices = serviceClient.get(key);
        if (CollectionUtils.isNotEmpty(namingServices)) {
            return namingServices.iterator().next();
        }
        log.warn("{} 无可用 namingservice", key);
        return null;
    }
    
    private static String buildClientKey(TaskDO taskDO) {
        return taskDO.getId() + ":" + taskDO.getServiceName();
    }
    
    private Instance buildSyncInstance(Instance instance, String destClusterId, String sourceClusterId, String syncSourceKey, String version) {
        Instance temp = new Instance();
        temp.setIp(instance.getIp());
        temp.setPort(instance.getPort());
        temp.setClusterName(instance.getClusterName());
        //查询源集群实例返回的serviceName含组名前缀，但Nacos2服务端检查批量注册请求serviceName参数时不能包含组名前缀，因此注册实例到目标集群时不再设置serviceName。
        temp.setEnabled(instance.isEnabled());
        temp.setHealthy(instance.isHealthy());
        temp.setWeight(instance.getWeight());
        temp.setEphemeral(instance.isEphemeral());
        Map<String, String> metaData = new HashMap<>(instance.getMetadata());
        metaData.put(SkyWalkerConstants.DEST_CLUSTERID_KEY, destClusterId);
        metaData.put(SkyWalkerConstants.SOURCE_CLUSTERID_KEY, sourceClusterId);
        metaData.put(SkyWalkerConstants.SYNC_SOURCE_KEY, syncSourceKey);
        //标识是同步实例
        metaData.put(SkyWalkerConstants.SYNC_INSTANCE_TAG, sourceClusterId + "@@" + version);
        temp.setMetadata(metaData);
        return temp;
    }
    
}
