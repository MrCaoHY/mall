-- 仅同步系统关闭的字段注释，不改变类型、非空、索引或外键，不修改既有业务记录。
-- 适用于已存在order_operation_logs的环境；本项目未启用Flyway，不会自动执行。
ALTER TABLE order_operation_logs
    MODIFY COLUMN operator_id BIGINT NOT NULL COMMENT '操作者编号：用户动作为可信用户ID，系统关闭为0',
    MODIFY COLUMN action VARCHAR(20) NOT NULL COMMENT '真实成功动作：CREATE、PAY、CANCEL、CLOSE';
