package cn.edu.tsinghua.iginx.monitor;

import cn.edu.tsinghua.iginx.conf.ConfigDescriptor;
import cn.edu.tsinghua.iginx.engine.shared.operator.OperatorType;
import cn.edu.tsinghua.iginx.metadata.entity.FragmentMeta;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RequestsMonitor implements IMonitor {

  private final boolean isEnableMonitor = ConfigDescriptor.getInstance().getConfig().isEnableMonitor();
  private final Map<FragmentMeta, Long> writeRequestsMap = new ConcurrentHashMap<>(); // 数据分区->请求个数
  private final Map<FragmentMeta, Long> readRequestsMap = new ConcurrentHashMap<>(); // 数据分区->请求个数
  private static final RequestsMonitor instance = new RequestsMonitor();

  public static RequestsMonitor getInstance() {
    return instance;
  }

  public Map<FragmentMeta, Long> getWriteRequestsMap() {
    return writeRequestsMap;
  }

  public Map<FragmentMeta, Long> getReadRequestsMap() {
    return readRequestsMap;
  }

  public void record(FragmentMeta fragmentMeta, OperatorType operatorType) {
    if (isEnableMonitor) {
      if (operatorType == OperatorType.Insert) {
        long count = writeRequestsMap.getOrDefault(fragmentMeta, 0L);
        count++;
        writeRequestsMap.put(fragmentMeta, count);
      } else if (operatorType == OperatorType.Project) {
        long count = readRequestsMap.getOrDefault(fragmentMeta, 0L);
        count++;
        readRequestsMap.put(fragmentMeta, count);
      }
    }
  }

  @Override
  public void clear() {
    writeRequestsMap.clear();
    readRequestsMap.clear();
  }
}