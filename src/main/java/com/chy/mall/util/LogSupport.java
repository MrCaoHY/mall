package com.chy.mall.util;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 日志公共约定：请求关联、事务真实结果和异常堆栈脱敏。
 * 只接收代码内定义的事件名与数值ID，不能传入请求体、Token、密码或数据库异常消息。
 */
public final class LogSupport {
    public static final String REQUEST_ID = "requestId";
    public static final String USER_ID = "userId";
    public static final String USER_ID_ATTRIBUTE = LogSupport.class.getName() + ".userId";
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    private LogSupport() {
    }

    /**
     * 在事务完成后记录最终结果，避免方法尚未提交就记录“创建成功”。
     * 捕获当前MDC；回调结束恢复原上下文，避免线程复用时串到下一次请求。
     * 无真实事务的Mock单测只输出DEBUG，不伪称数据库已经提交。
     */
    public static void transactionOutcome(Logger logger, String operation, Long entityId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            logger.debug("event=transaction_outcome operation={} entityId={} outcome=not_observed", operation, entityId);
            return;
        }
        Map<String, String> context = MDC.getCopyOfContextMap();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                restoreContext(context);
                try {
                    if (status == STATUS_COMMITTED) {
                        logger.info("event=transaction_outcome operation={} entityId={} outcome=committed", operation, entityId);
                    } else if (status == STATUS_ROLLED_BACK) {
                        logger.info("event=transaction_outcome operation={} entityId={} outcome=rolled_back", operation, entityId);
                    } else {
                        logger.warn("event=transaction_outcome operation={} entityId={} outcome=unknown", operation, entityId);
                    }
                } finally {
                    restoreContext(previous);
                }
            }
        });
    }

    /** 恢复整个MDC快照；不要只移除一个字段而遗留其他请求的上下文。 */
    public static void restoreContext(Map<String, String> context) {
        MDC.clear();
        if (context != null) {
            MDC.setContextMap(context);
        }
    }

    /**
     * 保留异常类型、调用栈和最多16层cause，但移除全部原始消息和suppressed异常。
     * JDBC/参数绑定异常的message可能包含密码Hash、SQL实参或连接信息，不能直接记录原异常。
     */
    public static Throwable safeStackTrace(Throwable exception) {
        return safeStackTrace(exception, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Throwable safeStackTrace(Throwable exception, Set<Throwable> visited, int depth) {
        if (exception == null || depth >= 16 || !visited.add(exception)) {
            return null;
        }
        RuntimeException safe = new RuntimeException(exception.getClass().getName(),
                safeStackTrace(exception.getCause(), visited, depth + 1));
        safe.setStackTrace(exception.getStackTrace());
        return safe;
    }
}
