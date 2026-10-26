# 订单操作记录

记录订单真实成功的CREATE、PAY、CANCEL，回答“谁在什么时候把订单从什么状态改成什么状态”。

## 表与事务

- 表：order_operation_logs，七个字段及中文注释见增量SQL。
- CREATE：NULL → PENDING_PAYMENT。
- PAY：PENDING_PAYMENT → PAID。
- CANCEL：PENDING_PAYMENT → CANCELLED。
- 同一订单同一动作唯一，仅适用于当前一次性创建/支付/取消生命周期。
- 流水只追加，没有updated_at；created_at使用对应业务的后端时间。
- 写入通过MyBatis-Plus BaseMapper，影响行数不是1就抛出异常。
- OrderOperationLogService.recordOperation使用MANDATORY，必须加入调用方已有事务。
- 创建流水与主表、明细、扣库存一起提交；取消流水与状态变化、库存恢复一起提交。
- 支付流水与支付状态一起提交。流水写入失败不被吞掉，整个业务事务回滚。
- 幂等重试提前返回原订单，不写新流水。
- 不补造历史订单记录：历史单查询可能返回空列表，也可能只有接入后发生的PAY/CANCEL。

## 数据库部署

项目目前没有启用Flyway。这里只保存版本化的增量文件，并不会自动运行：

`src/main/resources/db/migration/V20261021__order_operation_logs.sql`

新环境仅执行该增量CREATE TABLE，不要对已有数据库重跑完整schema.sql。当前本机学习库已由AI按授权执行并回查表结构。
订单外键没有级联删除；测试清理自己的订单前，先删除该订单流水。

## 查询接口

`GET /orders/{orderId}/operations`，使用订单主键和当前登录Token，无请求体。

成功返回HTTP200和统一ApiResponse，data为流水列表，按createdAt、id升序。只可查询自己的订单，不存在或不归属均404。
接口不接受客户端提交operatorId；写入操作者取业务入口的可信买家ID。

应用重新启动并加载最新代码后，可运行orderOperationTest.http中的00～08，最后检查新订单只有CREATE、PAY各一条。

## 验证与证据边界

- OrderOperationLogServiceTest：实体组装与零影响行数必须失败。
- OrderPayServiceTest、OrderServiceTest：原业务回归，并检查首次成功记流水、重试不记。
- OrderOperationIntegrationTest：显式开关运行真实MySQL，测试本身不加事务。
- 真实验收覆盖创建/支付/取消重试、顺序查询、归属、查询路由及流水写入后故障的整体回滚。
- 查询路由通过MockMvc模拟认证主体验证；不称为真实网络JWT登录验收。
- 集成测试只使用随机专用商品/订单，成功清理自己的流水、明细、订单和商品；失败保留现场。
- 流水失败实验先执行真实INSERT、在同事务验证中间数据存在，再抛受控异常；事务结束后独立回查。
- MANDATORY另验证无业务事务时直接拒绝，不落库。

运行真实库测试前，将IDEA MallApplication中已有的DB配置传入当前进程；测试内会生成临时JWT密钥，不需要打印或保存密钥：

```powershell
.\mvnw.cmd -o "-Dtest=OrderOperationIntegrationTest" "-Dmall.order.operations.enabled=true" test
```
