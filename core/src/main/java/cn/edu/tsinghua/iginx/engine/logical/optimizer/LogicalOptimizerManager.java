package cn.edu.tsinghua.iginx.engine.logical.optimizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogicalOptimizerManager {

    private final static Logger logger = LoggerFactory.getLogger(LogicalOptimizerManager.class);

    private final static LogicalOptimizerManager instance = new LogicalOptimizerManager();

    private final static String REMOVE_NOT = "remove_not";

    private LogicalOptimizerManager() {
    }

    public static LogicalOptimizerManager getInstance() {
        return instance;
    }

    public Optimizer getOptimizer(String name) {
        if (name == null || name.equals("")) {
            return null;
        }
        logger.info("use {} as logical optimizer.", name);

        switch (name) {
            case REMOVE_NOT:
                return RemoveNotOptimizer.getInstance();
            default:
                throw new IllegalArgumentException(String.format("unknown logical optimizer: %s", name));
        }
    }
}