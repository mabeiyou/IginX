/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package cn.edu.tsinghua.iginx.metadata;

import cn.edu.tsinghua.iginx.conf.ConfigDescriptor;
import cn.edu.tsinghua.iginx.conf.Constants;
import cn.edu.tsinghua.iginx.engine.physical.storage.StorageManager;
import cn.edu.tsinghua.iginx.exceptions.MetaStorageException;
import cn.edu.tsinghua.iginx.metadata.cache.DefaultMetaCache;
import cn.edu.tsinghua.iginx.metadata.cache.IMetaCache;
import cn.edu.tsinghua.iginx.metadata.entity.*;
import cn.edu.tsinghua.iginx.metadata.hook.StorageEngineChangeHook;
import cn.edu.tsinghua.iginx.metadata.hook.StorageUnitHook;
import cn.edu.tsinghua.iginx.metadata.storage.IMetaStorage;
import cn.edu.tsinghua.iginx.metadata.storage.etcd.ETCDMetaStorage;
import cn.edu.tsinghua.iginx.metadata.storage.file.FileMetaStorage;
import cn.edu.tsinghua.iginx.metadata.storage.zk.ZooKeeperMetaStorage;
import cn.edu.tsinghua.iginx.policy.simple.TimeSeriesCalDO;
import cn.edu.tsinghua.iginx.sql.statement.InsertStatement;
import cn.edu.tsinghua.iginx.thrift.AuthType;
import cn.edu.tsinghua.iginx.thrift.UserType;
import cn.edu.tsinghua.iginx.utils.Pair;
import cn.edu.tsinghua.iginx.utils.SnowFlakeUtils;
import cn.edu.tsinghua.iginx.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class DefaultMetaManager implements IMetaManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultMetaManager.class);
    private static volatile DefaultMetaManager INSTANCE;
    private final IMetaCache cache;

    private final IMetaStorage storage;
    private final List<StorageEngineChangeHook> storageEngineChangeHooks;
    private final List<StorageUnitHook> storageUnitHooks;
    private long id;

    private DefaultMetaManager() {
        cache = DefaultMetaCache.getInstance();

        switch (ConfigDescriptor.getInstance().getConfig().getMetaStorage()) {
            case Constants.ZOOKEEPER_META:
                logger.info("use zookeeper as meta storage.");
                storage = ZooKeeperMetaStorage.getInstance();
                break;
            case Constants.FILE_META:
                logger.info("use file as meta storage");
                storage = FileMetaStorage.getInstance();
                break;
            case Constants.ETCD_META:
                logger.info("use etcd as meta storage");
                storage = ETCDMetaStorage.getInstance();
                break;
            case "":
                //without configuration, file storage should be the safe choice
                logger.info("doesn't specify meta storage, use file as meta storage.");
                storage = FileMetaStorage.getInstance();
                break;
            default:
                //without configuration, file storage should be the safe choice
                logger.info("unknown meta storage, use file as meta storage.");
                storage = FileMetaStorage.getInstance();
                break;
        }

        storageEngineChangeHooks = Collections.synchronizedList(new ArrayList<>());
        storageUnitHooks = Collections.synchronizedList(new ArrayList<>());

        try {
            initIginx();
            initStorageEngine();
            initStorageUnit();
            initFragment();
            initSchemaMapping();
            initPolicy();
            initUser();
            initTransform();
        } catch (MetaStorageException e) {
            logger.error("init meta manager error: ", e);
            System.exit(-1);
        }
    }

    public static DefaultMetaManager getInstance() {
        if (INSTANCE == null) {
            synchronized (DefaultMetaManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new DefaultMetaManager();
                }
            }
        }
        return INSTANCE;
    }

    private void initIginx() throws MetaStorageException {
        storage.registerIginxChangeHook((id, iginx) -> {
            if (iginx == null) {
                cache.removeIginx(id);
            } else {
                cache.addIginx(iginx);
            }
        });
        for (IginxMeta iginx : storage.loadIginx().values()) {
            cache.addIginx(iginx);
        }
        IginxMeta iginx = new IginxMeta(0L, ConfigDescriptor.getInstance().getConfig().getIp(),
            ConfigDescriptor.getInstance().getConfig().getPort(), null);
        id = storage.registerIginx(iginx);
        SnowFlakeUtils.init(id);
    }

    private void initStorageEngine() throws MetaStorageException {
        storage.registerStorageChangeHook((id, storageEngine) -> {
            if (storageEngine != null) {
                if (storageEngine.isHasData()) {
                    StorageUnitMeta dummyStorageUnit = storageEngine.getDummyStorageUnit();
                    dummyStorageUnit.setStorageEngineId(id);
                    dummyStorageUnit.setId(String.format(Constants.DUMMY + "%04d", (int) id));
                    dummyStorageUnit.setMasterId(dummyStorageUnit.getId());
                    FragmentMeta dummyFragment = storageEngine.getDummyFragment();
                    dummyFragment.setMasterStorageUnit(dummyStorageUnit);
                    dummyFragment.setMasterStorageUnitId(dummyStorageUnit.getId());
                }
                cache.addStorageEngine(storageEngine);
                for (StorageEngineChangeHook hook : storageEngineChangeHooks) {
                    hook.onChanged(null, storageEngine);
                }
                if (storageEngine.isHasData()) {
                    for (StorageUnitHook storageUnitHook : storageUnitHooks) {
                        storageUnitHook.onChange(null, storageEngine.getDummyStorageUnit());
                    }
                }
            }
        });
        storage.loadStorageEngine(resolveStorageEngineFromConf());
    }

    private void initStorageUnit() throws MetaStorageException {
        storage.registerStorageUnitChangeHook((id, storageUnit) -> {
            if (storageUnit == null) {
                return;
            }
            if (storageUnit.getCreatedBy() == DefaultMetaManager.this.id) { // 本地创建的
                return;
            }
            if (storageUnit.isInitialStorageUnit()) { // 初始分片不通过异步事件更新
                return;
            }
            if (!cache.hasStorageUnit()) {
                return;
            }
            StorageUnitMeta originStorageUnitMeta = cache.getStorageUnit(id);
            if (originStorageUnitMeta == null) {
                if (!storageUnit.isMaster()) { // 需要加入到主节点的子节点列表中
                    StorageUnitMeta masterStorageUnitMeta = cache.getStorageUnit(storageUnit.getMasterId());
                    if (masterStorageUnitMeta == null) { // 子节点先于主节点加入系统中，不应该发生，报错
                        logger.error("unexpected storage unit " + storageUnit.toString() + ", because it does not has a master storage unit");
                    } else {
                        masterStorageUnitMeta.addReplica(storageUnit);
                    }
                }
            } else {
                if (storageUnit.isMaster()) {
                    storageUnit.setReplicas(originStorageUnitMeta.getReplicas());
                } else {
                    StorageUnitMeta masterStorageUnitMeta = cache.getStorageUnit(storageUnit.getMasterId());
                    if (masterStorageUnitMeta == null) { // 子节点先于主节点加入系统中，不应该发生，报错
                        logger.error("unexpected storage unit " + storageUnit.toString() + ", because it does not has a master storage unit");
                    } else {
                        masterStorageUnitMeta.removeReplica(originStorageUnitMeta);
                        masterStorageUnitMeta.addReplica(storageUnit);
                    }
                }
            }
            if (originStorageUnitMeta != null) {
                cache.updateStorageUnit(storageUnit);
                cache.getStorageEngine(storageUnit.getStorageEngineId()).removeStorageUnit(originStorageUnitMeta.getId());
            } else {
                cache.addStorageUnit(storageUnit);
            }
            cache.getStorageEngine(storageUnit.getStorageEngineId()).addStorageUnit(storageUnit);
            for (StorageUnitHook storageUnitHook : storageUnitHooks) {
                storageUnitHook.onChange(originStorageUnitMeta, storageUnit);
            }
        });
    }

    private void initFragment() throws MetaStorageException {
        storage.registerFragmentChangeHook((create, fragment) -> {
            if (fragment == null)
                return;
            if (create && fragment.getCreatedBy() == DefaultMetaManager.this.id) {
                return;
            }
            if (!create && fragment.getUpdatedBy() == DefaultMetaManager.this.id) {
                return;
            }
            if (fragment.isInitialFragment()) { // 初始分片不通过异步事件更新
                return;
            }
            if (!cache.hasFragment()) {
                return;
            }
            fragment.setMasterStorageUnit(cache.getStorageUnit(fragment.getMasterStorageUnitId()));
            if (create) {
                cache.addFragment(fragment);
            } else {
                cache.updateFragment(fragment);
            }
        });
    }

    private void initSchemaMapping() throws MetaStorageException {
        storage.registerSchemaMappingChangeHook((schema, schemaMapping) -> {
            if (schemaMapping == null || schemaMapping.size() == 0) {
                cache.removeSchemaMapping(schema);
            } else {
                cache.addOrUpdateSchemaMapping(schema, schemaMapping);
            }
        });
        for (Map.Entry<String, Map<String, Integer>> schemaEntry : storage.loadSchemaMapping().entrySet()) {
            cache.addOrUpdateSchemaMapping(schemaEntry.getKey(), schemaEntry.getValue());
        }
    }

    private void initPolicy() {
        storage.registerTimeseriesChangeHook(cache::timeSeriesIsUpdated);
        storage.registerVersionChangeHook((version, num) -> {
            double sum = cache.getSumFromTimeSeries();
            Map<String, Double> timeseriesData = cache.getMaxValueFromTimeSeries().stream().
                collect(Collectors.toMap(TimeSeriesCalDO::getTimeSeries, TimeSeriesCalDO::getValue));
            double countSum = timeseriesData.values().stream().mapToDouble(Double::doubleValue).sum();
            if (countSum > 1e-9) {
                timeseriesData.forEach((k, v) -> timeseriesData.put(k, v / countSum * sum));
            }
            try {
                storage.updateTimeseriesData(timeseriesData, getIginxId(), version);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        int num = 0;
        try {
            storage.registerPolicy(getIginxId(), num);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initUser() throws MetaStorageException {
        storage.registerUserChangeHook((username, user) -> {
            if (user == null) {
                cache.removeUser(username);
            } else {
                cache.addOrUpdateUser(user);
            }
        });
        for (UserMeta user : storage.loadUser(resolveUserFromConf())) {
            cache.addOrUpdateUser(user);
        }
    }

    private void initTransform() throws MetaStorageException {
        storage.registerTransformChangeHook(((name, transformTask) -> {
            if (transformTask == null) {
                cache.dropTransformTask(name);
            } else {
                cache.addOrUpdateTransformTask(transformTask);
            }
        }));
        for (TransformTaskMeta task: storage.loadTransformTask()) {
            cache.addOrUpdateTransformTask(task);
        }
    }

    @Override
    public boolean addStorageEngines(List<StorageEngineMeta> storageEngineMetas) {
        try {
            for (StorageEngineMeta storageEngineMeta : storageEngineMetas) {
                long id = storage.addStorageEngine(storageEngineMeta);
                storageEngineMeta.setId(id);
                if (storageEngineMeta.isHasData()) {
                    StorageUnitMeta dummyStorageUnit = storageEngineMeta.getDummyStorageUnit();
                    dummyStorageUnit.setStorageEngineId(id);
                    dummyStorageUnit.setId(String.format(Constants.DUMMY + "%04d", (int) id));
                    dummyStorageUnit.setMasterId(dummyStorageUnit.getId());
                    FragmentMeta dummyFragment = storageEngineMeta.getDummyFragment();
                    dummyFragment.setMasterStorageUnit(dummyStorageUnit);
                    dummyFragment.setMasterStorageUnitId(dummyStorageUnit.getId());
                }
                cache.addStorageEngine(storageEngineMeta);
            }
            return true;
        } catch (MetaStorageException e) {
            logger.error("add storage engines error:", e);
        }
        return false;
    }

    @Override
    public List<StorageEngineMeta> getStorageEngineList() {
        return new ArrayList<>(cache.getStorageEngineList());
    }

    @Override
    public List<StorageEngineMeta> getWriteableStorageEngineList() {
        return cache.getStorageEngineList().stream().filter(e -> !e.isReadOnly()).collect(Collectors.toList());
    }

    @Override
    public int getStorageEngineNum() {
        return cache.getStorageEngineList().size();
    }

    @Override
    public StorageEngineMeta getStorageEngine(long id) {
        return cache.getStorageEngine(id);
    }

    @Override
    public StorageUnitMeta getStorageUnit(String id) {
        return cache.getStorageUnit(id);
    }

    @Override
    public Map<String, StorageUnitMeta> getStorageUnits(Set<String> ids) {
        return cache.getStorageUnits(ids);
    }

    @Override
    public List<StorageUnitMeta> getStorageUnits() {
        return cache.getStorageUnits();
    }

    @Override
    public List<IginxMeta> getIginxList() {
        return new ArrayList<>(cache.getIginxList());
    }

    @Override
    public long getIginxId() {
        return id;
    }

    @Override
    public Map<TimeSeriesInterval, List<FragmentMeta>> getFragmentMapByTimeSeriesInterval(TimeSeriesInterval tsInterval) {
        return getFragmentMapByTimeSeriesInterval(tsInterval, false);
    }

    @Override
    public Map<TimeSeriesInterval, List<FragmentMeta>> getFragmentMapByTimeSeriesInterval(TimeSeriesInterval tsInterval, boolean withDummyFragment) {
        Map<TimeSeriesInterval, List<FragmentMeta>> fragmentsMap;
        if (cache.enableFragmentCacheControl() && cache.getFragmentMinTimestamp() > 0L) { // 最老的分片被逐出去了
            TimeInterval beforeTimeInterval = new TimeInterval(0L, cache.getFragmentMinTimestamp());
            fragmentsMap = storage.getFragmentMapByTimeSeriesIntervalAndTimeInterval(tsInterval, beforeTimeInterval);
            updateStorageUnitReference(fragmentsMap);
            Map<TimeSeriesInterval, List<FragmentMeta>> recentFragmentsMap = cache.getFragmentMapByTimeSeriesInterval(tsInterval);
            for (TimeSeriesInterval ts: recentFragmentsMap.keySet()) {
                List<FragmentMeta> fragments = recentFragmentsMap.get(ts);
                if (fragmentsMap.containsKey(ts)) {
                    fragmentsMap.get(ts).addAll(fragments);
                } else {
                    recentFragmentsMap.put(ts, fragments);
                }
            }
        } else {
            fragmentsMap = cache.getFragmentMapByTimeSeriesInterval(tsInterval);
        }
        if (withDummyFragment) {
            List<FragmentMeta> fragmentList = cache.getDummyFragmentsByTimeSeriesInterval(tsInterval);
            mergeToFragmentMap(fragmentsMap, fragmentList);
        }
        return fragmentsMap;
    }

    @Override
    public Map<TimeSeriesInterval, FragmentMeta> getLatestFragmentMapByTimeSeriesInterval(TimeSeriesInterval tsInterval) {
        return cache.getLatestFragmentMapByTimeSeriesInterval(tsInterval);
    }

    @Override
    public Map<TimeSeriesInterval, FragmentMeta> getLatestFragmentMap() {
        return cache.getLatestFragmentMap();
    }

    @Override
    public Map<TimeSeriesInterval, List<FragmentMeta>> getFragmentMapByTimeSeriesIntervalAndTimeInterval(TimeSeriesInterval tsInterval, TimeInterval timeInterval) {
        return getFragmentMapByTimeSeriesIntervalAndTimeInterval(tsInterval, timeInterval, false);
    }

    @Override
    public Map<TimeSeriesInterval, List<FragmentMeta>> getFragmentMapByTimeSeriesIntervalAndTimeInterval(TimeSeriesInterval tsInterval, TimeInterval timeInterval, boolean withDummyFragment) {
        Map<TimeSeriesInterval, List<FragmentMeta>> fragmentsMap;
        if (cache.enableFragmentCacheControl() && timeInterval.getStartTime() < cache.getFragmentMinTimestamp()) {
            TimeInterval beforeTimeInterval = new TimeInterval(timeInterval.getStartTime(), cache.getFragmentMinTimestamp());
            fragmentsMap = storage.getFragmentMapByTimeSeriesIntervalAndTimeInterval(tsInterval, beforeTimeInterval);
            updateStorageUnitReference(fragmentsMap);
            Map<TimeSeriesInterval, List<FragmentMeta>> recentFragmentsMap = cache.getFragmentMapByTimeSeriesIntervalAndTimeInterval(tsInterval, timeInterval);
            for (TimeSeriesInterval ts: recentFragmentsMap.keySet()) {
                List<FragmentMeta> fragments = recentFragmentsMap.get(ts);
                if (fragmentsMap.containsKey(ts)) {
                    fragmentsMap.get(ts).addAll(fragments);
                } else {
                    recentFragmentsMap.put(ts, fragments);
                }
            }
        } else {
            fragmentsMap = cache.getFragmentMapByTimeSeriesIntervalAndTimeInterval(tsInterval, timeInterval);
        }
        if (withDummyFragment) {
            List<FragmentMeta> fragmentList = cache.getDummyFragmentsByTimeSeriesIntervalAndTimeInterval(tsInterval, timeInterval);
            mergeToFragmentMap(fragmentsMap, fragmentList);
        }
        return fragmentsMap;
    }

    private void mergeToFragmentMap(Map<TimeSeriesInterval, List<FragmentMeta>> fragmentsMap, List<FragmentMeta> fragmentList) {
        for (FragmentMeta fragment: fragmentList) {
            TimeSeriesInterval tsInterval = fragment.getTsInterval();
            if (!fragmentsMap.containsKey(tsInterval)) {
                fragmentsMap.put(tsInterval, new ArrayList<>());
            }
            List<FragmentMeta> currentFragmentList = fragmentsMap.get(tsInterval);
            int index = 0;
            while (index < currentFragmentList.size()) {
                if (currentFragmentList.get(index).getTimeInterval().getStartTime() <= fragment.getTimeInterval().getStartTime()) {
                    index++;
                } else {
                    break;
                }
            }
            currentFragmentList.add(index, fragment);
        }
    }

    @Override
    public List<FragmentMeta> getFragmentListByTimeSeriesName(String tsName) {
        if (cache.enableFragmentCacheControl() && cache.getFragmentMinTimestamp() > 0L) {
            TimeInterval beforeTimeInterval = new TimeInterval(0L, cache.getFragmentMinTimestamp());
            List<FragmentMeta> fragments = storage.getFragmentListByTimeSeriesNameAndTimeInterval(tsName, beforeTimeInterval);
            updateStorageUnitReference(fragments);
            fragments.addAll(cache.getFragmentListByTimeSeriesName(tsName));
            return fragments;
        }
        return cache.getFragmentListByTimeSeriesName(tsName);
    }

    @Override
    public FragmentMeta getLatestFragmentByTimeSeriesName(String tsName) { // 最新的分片数据必须被缓存
        return cache.getLatestFragmentByTimeSeriesName(tsName);
    }

    @Override
    public List<FragmentMeta> getFragmentListByTimeSeriesNameAndTimeInterval(String tsName, TimeInterval timeInterval) {
        if (cache.enableFragmentCacheControl() && timeInterval.getStartTime() < cache.getFragmentMinTimestamp()) {
            TimeInterval beforeTimeInterval = new TimeInterval(timeInterval.getStartTime(), cache.getFragmentMinTimestamp());
            List<FragmentMeta> fragments = storage.getFragmentListByTimeSeriesNameAndTimeInterval(tsName, beforeTimeInterval);
            updateStorageUnitReference(fragments);
            fragments.addAll(cache.getFragmentListByTimeSeriesNameAndTimeInterval(tsName, timeInterval));
            return fragments;
        }
        return cache.getFragmentListByTimeSeriesNameAndTimeInterval(tsName, timeInterval);
    }

    @Override
    public boolean createFragmentsAndStorageUnits(List<StorageUnitMeta> storageUnits, List<FragmentMeta> fragments) {
        try {
            storage.lockFragment();
            storage.lockStorageUnit();

            Map<String, StorageUnitMeta> fakeIdToStorageUnit = new HashMap<>(); // 假名翻译工具
            for (StorageUnitMeta masterStorageUnit : storageUnits) {
                masterStorageUnit.setCreatedBy(id);
                String fakeName = masterStorageUnit.getId();
                String actualName = storage.addStorageUnit();
                StorageUnitMeta actualMasterStorageUnit = masterStorageUnit.renameStorageUnitMeta(actualName, actualName);
                cache.updateStorageUnit(actualMasterStorageUnit);
                for (StorageUnitHook hook : storageUnitHooks) {
                    hook.onChange(null, actualMasterStorageUnit);
                }
                storage.updateStorageUnit(actualMasterStorageUnit);
                fakeIdToStorageUnit.put(fakeName, actualMasterStorageUnit);
                for (StorageUnitMeta slaveStorageUnit : masterStorageUnit.getReplicas()) {
                    slaveStorageUnit.setCreatedBy(id);
                    String slaveFakeName = slaveStorageUnit.getId();
                    String slaveActualName = storage.addStorageUnit();
                    StorageUnitMeta actualSlaveStorageUnit = slaveStorageUnit.renameStorageUnitMeta(slaveActualName, actualName);
                    actualMasterStorageUnit.addReplica(actualSlaveStorageUnit);
                    for (StorageUnitHook hook : storageUnitHooks) {
                        hook.onChange(null, actualSlaveStorageUnit);
                    }
                    cache.updateStorageUnit(actualSlaveStorageUnit);
                    storage.updateStorageUnit(actualSlaveStorageUnit);
                    fakeIdToStorageUnit.put(slaveFakeName, actualSlaveStorageUnit);
                }
            }

            Map<TimeSeriesInterval, FragmentMeta> latestFragments = getLatestFragmentMap();
            for (FragmentMeta originalFragmentMeta : latestFragments.values()) {
                FragmentMeta fragmentMeta = originalFragmentMeta.endFragmentMeta(fragments.get(0).getTimeInterval().getStartTime());
                // 在更新分片时，先更新本地
                fragmentMeta.setUpdatedBy(id);
                cache.updateFragment(fragmentMeta);
                storage.updateFragment(fragmentMeta);
            }

            for (FragmentMeta fragmentMeta : fragments) {
                fragmentMeta.setCreatedBy(id);
                fragmentMeta.setInitialFragment(false);
                StorageUnitMeta storageUnit = fakeIdToStorageUnit.get(fragmentMeta.getFakeStorageUnitId());
                if (storageUnit.isMaster()) {
                    fragmentMeta.setMasterStorageUnit(storageUnit);
                } else {
                    fragmentMeta.setMasterStorageUnit(getStorageUnit(storageUnit.getMasterId()));
                }
                cache.addFragment(fragmentMeta);
                storage.addFragment(fragmentMeta);
            }
            return true;
        } catch (MetaStorageException e) {
            logger.error("create fragment error: ", e);
        } finally {
            try {
                storage.releaseFragment();
                storage.releaseStorageUnit();
            } catch (MetaStorageException e) {
                logger.error("release fragment lock error: ", e);
            }
        }
        return false;
    }

    @Override
    public boolean hasFragment() {
        return cache.hasFragment();
    }

    @Override
    public boolean createInitialFragmentsAndStorageUnits(List<StorageUnitMeta> storageUnits, List<FragmentMeta> initialFragments) { // 必须同时初始化 fragment 和 cache，并且这个方法的主体部分在任意时刻只能由某个 iginx 的某个线程执行
        if (cache.hasFragment() && cache.hasStorageUnit()) {
            return false;
        }
        List<StorageUnitMeta> newStorageUnits = new ArrayList<>();
        try {
            storage.lockFragment();
            storage.lockStorageUnit();

            // 接下来的部分只有一个 iginx 的一个线程执行
            if (cache.hasFragment() && cache.hasStorageUnit()) {
                return false;
            }
            // 查看一下服务器上是不是已经有了
            Map<String, StorageUnitMeta> globalStorageUnits = storage.loadStorageUnit();
            if (globalStorageUnits != null && !globalStorageUnits.isEmpty()) { // 服务器上已经有人创建过了，本地只需要加载
                Map<TimeSeriesInterval, List<FragmentMeta>> globalFragmentMap = storage.loadFragment();
                newStorageUnits.addAll(globalStorageUnits.values());
                newStorageUnits.sort(Comparator.comparing(StorageUnitMeta::getId));
                logger.warn("server has created storage unit, just need to load.");
                logger.warn("notify storage unit listeners.");
                for (StorageUnitHook hook : storageUnitHooks) {
                    for (StorageUnitMeta meta : newStorageUnits) {
                        hook.onChange(null, meta);
                    }
                }
                logger.warn("notify storage unit listeners finished.");
                // 再初始化缓存
                cache.initStorageUnit(globalStorageUnits);
                cache.initFragment(globalFragmentMap);
                return false;
            }

            // 确实没有人创建过，以我为准
            Map<String, StorageUnitMeta> fakeIdToStorageUnit = new HashMap<>(); // 假名翻译工具
            for (StorageUnitMeta masterStorageUnit : storageUnits) {
                masterStorageUnit.setCreatedBy(id);
                String fakeName = masterStorageUnit.getId();
                String actualName = storage.addStorageUnit();
                StorageUnitMeta actualMasterStorageUnit = masterStorageUnit.renameStorageUnitMeta(actualName, actualName);
                storage.updateStorageUnit(actualMasterStorageUnit);
                fakeIdToStorageUnit.put(fakeName, actualMasterStorageUnit);
                for (StorageUnitMeta slaveStorageUnit : masterStorageUnit.getReplicas()) {
                    slaveStorageUnit.setCreatedBy(id);
                    String slaveFakeName = slaveStorageUnit.getId();
                    String slaveActualName = storage.addStorageUnit();
                    StorageUnitMeta actualSlaveStorageUnit = slaveStorageUnit.renameStorageUnitMeta(slaveActualName, actualName);
                    actualMasterStorageUnit.addReplica(actualSlaveStorageUnit);
                    storage.updateStorageUnit(actualSlaveStorageUnit);
                    fakeIdToStorageUnit.put(slaveFakeName, actualSlaveStorageUnit);
                }
            }
            initialFragments.sort(Comparator.comparingLong(o -> o.getTimeInterval().getStartTime()));
            for (FragmentMeta fragmentMeta : initialFragments) {
                fragmentMeta.setCreatedBy(id);
                StorageUnitMeta storageUnit = fakeIdToStorageUnit.get(fragmentMeta.getFakeStorageUnitId());
                if (storageUnit.isMaster()) {
                    fragmentMeta.setMasterStorageUnit(storageUnit);
                } else {
                    fragmentMeta.setMasterStorageUnit(getStorageUnit(storageUnit.getMasterId()));
                }
                storage.addFragment(fragmentMeta);
            }
            Map<String, StorageUnitMeta> loadedStorageUnits = storage.loadStorageUnit();
            newStorageUnits.addAll(loadedStorageUnits.values());
            newStorageUnits.sort(Comparator.comparing(StorageUnitMeta::getId));
            // 先通知
            logger.warn("i have created storage unit.");
            logger.warn("notify storage unit listeners.");
            for (StorageUnitHook hook : storageUnitHooks) {
                for (StorageUnitMeta meta : newStorageUnits) {
                    hook.onChange(null, meta);
                }
            }
            logger.warn("notify storage unit listeners finished.");
            // 再初始化缓存
            cache.initStorageUnit(loadedStorageUnits);
            cache.initFragment(storage.loadFragment());
            return true;
        } catch (MetaStorageException e) {
            logger.error("encounter error when init fragment: ", e);
        } finally {
            try {
                storage.releaseStorageUnit();
                storage.releaseFragment();
            } catch (MetaStorageException e) {
                logger.error("encounter error when release fragment lock: ", e);
            }
        }
        return false;
    }

    @Override
    public List<Long> selectStorageEngineIdList() {
        List<Long> storageEngineIdList = getWriteableStorageEngineList().stream().map(StorageEngineMeta::getId).collect(Collectors.toList());
        if (storageEngineIdList.size() <= 1 + ConfigDescriptor.getInstance().getConfig().getReplicaNum()) {
            return storageEngineIdList;
        }
        Random random = new Random();
        for (int i = 0; i < storageEngineIdList.size(); i++) {
            int next = random.nextInt(storageEngineIdList.size());
            Long value = storageEngineIdList.get(next);
            storageEngineIdList.set(next, storageEngineIdList.get(i));
            storageEngineIdList.set(i, value);
        }
        return storageEngineIdList.subList(0, 1 + ConfigDescriptor.getInstance().getConfig().getReplicaNum());
    }

    @Override
    public void registerStorageEngineChangeHook(StorageEngineChangeHook hook) {
        if (hook != null) {
            this.storageEngineChangeHooks.add(hook);
        }
    }

    @Override
    public void addOrUpdateSchemaMapping(String schema, Map<String, Integer> schemaMapping) {
        try {
            storage.updateSchemaMapping(schema, schemaMapping);
            if (schemaMapping == null) {
                cache.removeSchemaMapping(schema);
            } else {
                cache.addOrUpdateSchemaMapping(schema, schemaMapping);
            }
        } catch (MetaStorageException e) {
            logger.error("update schema mapping error: ", e);
        }
    }

    @Override
    public void addOrUpdateSchemaMappingItem(String schema, String key, int value) {
        Map<String, Integer> schemaMapping = cache.getSchemaMapping(schema);
        if (schemaMapping == null) {
            schemaMapping = new HashMap<>();
        }
        if (value == -1) {
            schemaMapping.remove(key);
        } else {
            schemaMapping.put(key, value);
        }
        try {
            storage.updateSchemaMapping(schema, schemaMapping);
            if (value == -1) {
                cache.removeSchemaMappingItem(schema, key);
            } else {
                cache.addOrUpdateSchemaMappingItem(schema, key, value);
            }
        } catch (MetaStorageException e) {
            logger.error("update schema mapping error: ", e);
        }
    }

    @Override
    public Map<String, Integer> getSchemaMapping(String schema) {
        return cache.getSchemaMapping(schema);
    }

    @Override
    public int getSchemaMappingItem(String schema, String key) {
        return cache.getSchemaMappingItem(schema, key);
    }

    private List<StorageEngineMeta> resolveStorageEngineFromConf() {
        List<StorageEngineMeta> storageEngineMetaList = new ArrayList<>();
        String[] storageEngineStrings = ConfigDescriptor.getInstance().getConfig().getStorageEngineList().split(",");
        for (int i = 0; i < storageEngineStrings.length; i++) {
            if (storageEngineStrings[i].length() == 0) {
                continue;
            }
            String[] storageEngineParts = storageEngineStrings[i].split("#");
            String ip = storageEngineParts[0];
            int port = Integer.parseInt(storageEngineParts[1]);
            String storageEngine = storageEngineParts[2];
            Map<String, String> extraParams = new HashMap<>();
            String[] KAndV;
            for (int j = 3; j < storageEngineParts.length; j++) {
                if (storageEngineParts[j].contains("\"")) {
                    KAndV = storageEngineParts[j].split("\"");
                    extraParams.put(KAndV[0].substring(0, KAndV[0].length() - 1), KAndV[1]);
                } else {
                    KAndV = storageEngineParts[j].split("=");
                    if (KAndV.length != 2) {
                        logger.error("unexpected storage engine meta info: " + storageEngineStrings[i]);
                        continue;
                    }
                    extraParams.put(KAndV[0], KAndV[1]);
                }
            }
            boolean hasData = Boolean.parseBoolean(extraParams.getOrDefault(Constants.HAS_DATA, "false"));
            String dataPrefix = null;
            if (hasData && extraParams.containsKey(Constants.DATA_PREFIX)) {
                dataPrefix = extraParams.get(Constants.DATA_PREFIX);
            }
            boolean readOnly = Boolean.parseBoolean(extraParams.getOrDefault(Constants.IS_READ_ONLY, "false"));
            StorageEngineMeta storage = new StorageEngineMeta(i, ip, port, hasData, dataPrefix, readOnly, extraParams, storageEngine, id);
            if (hasData) {
                StorageUnitMeta dummyStorageUnit = new StorageUnitMeta(Constants.DUMMY + String.format("%04d", i), i);
                Pair<TimeSeriesInterval, TimeInterval> boundary = StorageManager.getBoundaryOfStorage(storage);
                FragmentMeta dummyFragment;
                if (dataPrefix == null) {
                    dummyFragment = new FragmentMeta(boundary.k, boundary.v, dummyStorageUnit);
                } else {
                    dummyFragment = new FragmentMeta(new TimeSeriesInterval(dataPrefix, StringUtils.nextString(dataPrefix)), boundary.v, dummyStorageUnit);
                }
                storage.setDummyStorageUnit(dummyStorageUnit);
                storage.setDummyFragment(dummyFragment);
            }
            storageEngineMetaList.add(storage);
        }
        return storageEngineMetaList;
    }

    private UserMeta resolveUserFromConf() {
        String username = ConfigDescriptor.getInstance().getConfig().getUsername();
        String password = ConfigDescriptor.getInstance().getConfig().getPassword();
        UserType userType = UserType.Administrator;
        Set<AuthType> auths = new HashSet<>();
        auths.add(AuthType.Read);
        auths.add(AuthType.Write);
        auths.add(AuthType.Admin);
        auths.add(AuthType.Cluster);
        return new UserMeta(username, password, userType, auths);
    }

    @Override
    public boolean addUser(UserMeta user) {
        try {
            storage.addUser(user);
            cache.addOrUpdateUser(user);
            return true;
        } catch (MetaStorageException e) {
            logger.error("add user error: ", e);
            return false;
        }
    }

    @Override
    public boolean updateUser(String username, String password, Set<AuthType> auths) {
        List<UserMeta> users = cache.getUser(Collections.singletonList(username));
        if (users.size() == 0) { // 待更新的用户不存在
            return false;
        }
        UserMeta user = users.get(0);
        if (password != null) {
            user.setPassword(password);
        }
        if (auths != null) {
            user.setAuths(auths);
        }
        try {
            storage.updateUser(user);
            cache.addOrUpdateUser(user);
            return true;
        } catch (MetaStorageException e) {
            logger.error("update user error: ", e);
            return false;
        }
    }

    @Override
    public boolean removeUser(String username) {
        try {
            storage.removeUser(username);
            cache.removeUser(username);
            return true;
        } catch (MetaStorageException e) {
            logger.error("remove user error: ", e);
            return false;
        }
    }

    @Override
    public UserMeta getUser(String username) {
        List<UserMeta> users = cache.getUser(Collections.singletonList(username));
        if (users.size() == 0) {
            return null;
        }
        return users.get(0);
    }

    protected void updateStorageUnitReference(Map<TimeSeriesInterval, List<FragmentMeta>> fragmentsMap) {
        for (List<FragmentMeta> fragments: fragmentsMap.values()) {
            for (FragmentMeta fragment: fragments) {
                fragment.setMasterStorageUnit(cache.getStorageUnit(fragment.getMasterStorageUnitId()));
            }
        }
    }

    protected void updateStorageUnitReference(List<FragmentMeta> fragments) {
        for (FragmentMeta fragment: fragments) {
            fragment.setMasterStorageUnit(cache.getStorageUnit(fragment.getMasterStorageUnitId()));
        }
    }

    @Override
    public List<UserMeta> getUsers() {
        return cache.getUser();
    }

    @Override
    public List<UserMeta> getUsers(List<String> username) {
        return cache.getUser(username);
    }

    @Override
    public void registerStorageUnitHook(StorageUnitHook hook) {
        this.storageUnitHooks.add(hook);
    }

    @Override
    public boolean election() {
        return storage.election();
    }

    @Override
    public void saveTimeSeriesData(InsertStatement statement) {
        cache.saveTimeSeriesData(statement);
    }

    @Override
    public List<TimeSeriesCalDO> getMaxValueFromTimeSeries() {
        return cache.getMaxValueFromTimeSeries();
    }

    @Override
    public Map<String, Double> getTimeseriesData() {
        return storage.getTimeseriesData();
    }

    @Override
    public int updateVersion() {
        return storage.updateVersion();
    }

    @Override
    public Map<Integer, Integer> getTimeseriesVersionMap() {
        return cache.getTimeseriesVersionMap();
    }

    @Override
    public boolean addTransformTask(TransformTaskMeta transformTask) {
        try {
            storage.addTransformTask(transformTask);
            cache.addOrUpdateTransformTask(transformTask);
            return true;
        } catch (MetaStorageException e) {
            logger.error("add transform task error: ", e);
            return false;
        }
    }

    @Override
    public boolean updateTransformTask(TransformTaskMeta transformTask) {
        try {
            storage.updateTransformTask(transformTask);
            cache.addOrUpdateTransformTask(transformTask);
            return true;
        } catch (MetaStorageException e) {
            logger.error("add transform task error: ", e);
            return false;
        }
    }

    @Override
    public boolean dropTransformTask(String name) {
        try {
            cache.dropTransformTask(name);
            storage.dropTransformTask(name);
            return true;
        } catch (MetaStorageException e) {
            logger.error("drop transform task error: ", e);
            return false;
        }
    }

    @Override
    public TransformTaskMeta getTransformTask(String name) {
        return cache.getTransformTask(name);
    }

    @Override
    public List<TransformTaskMeta> getTransformTasks() {
        return cache.getTransformTasks();
    }
}
