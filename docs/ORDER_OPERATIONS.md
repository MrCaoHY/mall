# 订单操作记录

记录订单真实成功的CREATE、PAY、CANCEL、CLOSE，回答“谁在什么时候把订单从什么状态改成什么状态”。

## 表与事务

- 表：order_operation_logs，七个字段及中文注释见增量SQL。
- CREATE：NULL → PENDING_PAYMENT。
- PAY：PENDING_PAYMENT → PAID。
- CANCEL：PENDING_PAYMENT → CANCELLED。
- CLOSE：PENDING_PAYMENT → CLOSED。
- 用户动作的operator_id必须为可信正数用户ID，CLOSE仅允许系统编号0。
- 同一订单同一动作唯一，仅适用于当前一次性创建/支付/取消/关闭生命周期。
- 流水只追加，没有updated_at；created_at使用对应业务的后端时间。
- 写入通过MyBatis-Plus BaseMapper，影响行数不是1就抛出异常。
- OrderOperationLogService.recordOperation使用MANDATORY，必须加入调用方已有事务。
- 创建流水与主表、明细、扣库存一起提交；取消流水与状态变化、库存恢复一起提交。
- 支付流水与支付状态一起提交；关闭流水与关闭状态、库存恢复一起提交。流水失败向外抛出，整个业务事务回滚。
- 幂等重试提前返回原订单，不写新流水。
- 不补造历史订单记录：历史单查询可能返回空列表，也可能只有接入后发生的PAY/CANCEL。

## 数据库部署

项目目前没有启用Flyway。这里只保存版本化的增量文件，并不会自动运行：

`src/main/resources/db/migration/V20261021__order_operation_logs.sql`

新环境仅执行该增量CREATE TABLE，不要对已有数据库重跑完整schema.sql。当前本机学习库已由AI按授权执行并回查表结构。
订单外键没有级联删除；测试清理自己的订单前，先删除该订单流水。

## 单笔超时关闭

OrderCloseTransactionService.closeExpiredOrder(orderId)是系统内部事务入口，目前尚未接定时扫描或对外关闭接口。

- 仅id匹配、PENDING_PAYMENT且expires_at<=本次后端时间时更新为CLOSED。
- 未取得资格返回false，不恢复库存、不刷新时间、不写流水；成功关闭返回true。
- 状态更新影响1行后，按商品ID顺序原子加回全部购买数量，每项恢复必须影响1行。
- 所有库存和CLOSE流水都在同一事务，异常不吞掉；使用事务完成回调记录实际结果。
- 不使用cancelled_at表示系统关闭；关闭时间取updated_at及CLOSE流水created_at。
- 同时触发或重复触发也只能成功转换一次，不依赖调用前查询判断资格。

本次同时提供V20261027__order_operation_close_comments.sql，仅为已有表同步CLOSE和系统操作者0的字段注释，不改变结构或数据；该注释增量尚未代执行。当前schema.sql已包含最新说明，历史V20261021建表迁移保持原样。新环境与已有环境按实际建表状态选用，不能重跑CREATE TABLE覆盖现有库。

## 查询接口

`GET /orders/{orderId}/operations`，使用订单主键和当前登录Token，无请求体。

成功返回HTTP200和统一ApiResponse，data为流水列表，按createdAt、id升序。只可查询自己的订单，不存在或不归属均404。
接口不接受客户端提交operatorId；用户动作取可信买家ID，系统关闭固定使用0。

应用重新启动并加载最新代码后，可运行orderOperationTest.http中的00～08，最后检查新订单只有CREATE、PAY各一条。

## 验证与证据边界

- OrderOperationLogServiceTest：实体组装与零影响行数必须失败。
- OrderPayServiceTest、OrderServiceTest：原业务回归，并检查首次成功记流水、重试不记。
- OrderOperationIntegrationTest：显式开关运行真实MySQL，测试本身不加事务。
- 真实验收覆盖创建/支付/取消/关闭重试、顺序查询、归属、查询路由及流水真实写入后故障的整体回滚。
- 关闭覆盖未到期、已支付、已取消和不存在订单的安全跳过，以及多商品中途恢复失败的整体回滚。
- OrderCancelIntegrationTest覆盖支付/取消、取消/关闭和两个关闭请求的真实竞争，检查实际行数、库存和流水；停止线程后才清理。
- 查询路由通过MockMvc模拟认证主体验证；不称为真实网络JWT登录验收。
- 集成测试只使用随机专用商品/订单，成功清理自己的流水、明细、订单和商品；失败保留现场。
- 流水失败实验先执行真实INSERT、在同事务验证中间数据存在，再抛受控异常；事务结束后独立回查。
- MANDATORY另验证无业务事务时直接拒绝，不落库。

批量运行前，将IDEA MallApplication已有的测试运行环境变量传入当前进程，不打印或提交凭据。新增集成类自行生成临时JWT；旧OrderCreateRollbackTest沿用进程中的有效JWT配置。最终受影响回归命令：

```powershell
.\mvnw.cmd -o "-Dtest=OrderOperationLogServiceTest,OrderPayServiceTest,OrderServiceTest,OrderOperationIntegrationTest,OrderCancelIntegrationTest,OrderIdempotentConcurrentTest,OrderCreateRollbackTest" "-Dmall.order.operations.enabled=true" "-Dmall.order.cancel.enabled=true" "-Dmall.order.concurrent.enabled=true" "-Dmall.order.rollback.enabled=true" test
```
2026-10-27已执行上述7类组合回归：共31项（8项轻量单测、23项真实MySQL集成），Failures=0、Errors=0、Skipped=0，BUILD SUCCESS。两项收尾补测分别验证多商品关闭在第二份库存缺失时整体回滚、两个系统关闭并发时实际更新1/0且只恢复一次库存。该结果覆盖当前单笔关闭及受影响业务，不包含尚未实现的定时扫描或真实网络HTTP验收；测试由AI按授权编写和执行，不计为学员独立掌握。
