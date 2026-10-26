package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.entity.OrderOperationLogDo;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.mapper.OrderOperationLogMapper;
import com.chy.mall.vo.OrderOperationLogVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 操作流水的事务内写入和顺序查询；这里不承担订单状态转换或库存处理。 */
@Service
@RequiredArgsConstructor
public class OrderOperationLogService {
    private final OrderOperationLogMapper operationLogMapper;

    /**
     * 强制加入调用方已有事务，不能自行提交流水。
     * 插入失败直接向外抛出，让订单状态、库存和流水一起回滚，不吞掉唯一键异常。
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recordOperation(Long orderId, Long operatorId, OrderOperationAction action, LocalDateTime operationTime) {
        if (orderId == null || orderId <= 0 || operatorId == null
                || action == null || operationTime == null) {
            throw new IllegalArgumentException("订单流水内部参数不合法");
        }
        // 自动关闭使用系统编号0；创建、支付和取消必须由正数用户ID执行。
        boolean validOperator = action == OrderOperationAction.CLOSE ? operatorId == 0L : operatorId > 0;
        if (!validOperator) {
            throw new IllegalArgumentException("订单流水操作者与动作不匹配");
        }

        OrderOperationLogDo operation = new OrderOperationLogDo();
        operation.setOrderId(orderId);
        operation.setOperatorId(operatorId);
        operation.setAction(action);
        operation.setFromStatus(action.getFromStatus());
        operation.setToStatus(action.getToStatus());
        operation.setCreatedAt(operationTime);
        if (operationLogMapper.insert(operation) != 1) {
            throw new IllegalStateException("订单操作记录保存失败");
        }
    }

    /** 仅供已核对订单归属的入口调用；同一秒内按主键排序，不为历史订单补造流水。 */
    public List<OrderOperationLogVo> listByOrderId(Long orderId) {
        return operationLogMapper.selectList(Wrappers.<OrderOperationLogDo>lambdaQuery()
                        .eq(OrderOperationLogDo::getOrderId, orderId)
                        .orderByAsc(OrderOperationLogDo::getCreatedAt, OrderOperationLogDo::getId))
                .stream().map(operation -> {
                    OrderOperationLogVo result = new OrderOperationLogVo();
                    result.setId(operation.getId());
                    result.setOrderId(operation.getOrderId());
                    result.setOperatorId(operation.getOperatorId());
                    result.setAction(operation.getAction());
                    result.setFromStatus(operation.getFromStatus());
                    result.setToStatus(operation.getToStatus());
                    result.setCreatedAt(operation.getCreatedAt());
                    return result;
                }).toList();
    }
}
