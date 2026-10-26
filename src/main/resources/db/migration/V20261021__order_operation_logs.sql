-- 独立增量脚本，仅创建新表；现有订单不回填，不重建或清空任何已有表。
-- 目前项目没有启用Flyway，此文件用于人工/工具执行与留存，不会自动重跑schema.sql。
CREATE TABLE order_operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单操作流水主键',
    order_id BIGINT NOT NULL COMMENT '所属订单主键，关联orders.id',
    operator_id BIGINT NOT NULL COMMENT '可信认证用户ID，创建、支付或取消的操作者',
    action VARCHAR(20) NOT NULL COMMENT '真实成功动作：CREATE、PAY、CANCEL',
    from_status VARCHAR(20) NULL COMMENT '动作前订单状态，CREATE时为空',
    to_status VARCHAR(20) NOT NULL COMMENT '动作后订单状态',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '服务端业务动作时间',
    -- 当前生命周期每种动作至多成功一次；重试由Service提前返回，不重复插入。
    UNIQUE KEY uk_order_operation_action (order_id, action),
    -- 保护业务历史，不允许删除订单时连带删除流水。
    CONSTRAINT fk_order_operation_order FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单成功操作记录';
