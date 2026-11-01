# 当前用户订单分页

GET /orders，携带当前用户登录Token，使用URL查询参数，不需要请求体。

- page：从1开始，默认1。
- size：1到100，默认10。
- status：可选PENDING_PAYMENT、PAID、CANCELLED、CLOSED；省略时查询所有状态。
- 买家ID取认证主体，传入buyerId不会切换查询用户；总数与记录使用同一买家/状态条件。
- 按created_at、id倒序排列；相同创建时间仍有稳定顺序。

例：GET /orders?page=1&size=10&status=PAID。
成功返回HTTP200和ApiResponse，data为PageVo：page、size、total、records。
records沿用OrderVo并包含items；当前页全部明细通过一次IN批量查询，避免逐订单查询。
无匹配结果返回total=0、records=[]；超过末页保留真实total，records=[]。
非法页码/数量/状态返回HTTP400。参数DTO不包含密码、Token或买家身份字段。

## 单笔详情与身份校验

GET /orders/{orderId}返回当前买家的单笔OrderVo及商品成交快照；非本人和不存在统一HTTP404/code40402，非正订单ID为HTTP400/code40001。
六个订单入口由Controller确认认证主体非空并提取买家ID，Service统一校验买家ID合法性，非法身份为HTTP403/code40301；订单归属条件继续在数据库查询或更新中保留。
当前application.yaml已启用自动关单，测试必须显式设置mall.order.close-schedule.enabled=false，避免处理已有订单。

## 验证

2026-11-01 21:52：统一身份校验后，查询轻量5、真实MySQL/MockMvc1、创建1、支付3、扫描轻量5、批处理真实库2和调度3，共20项通过，失败/错误/跳过均为0。额外验证6个Service入口对非法买家统一拒绝，以及查询接口HTTP403/code40301；原详情成交快照、归属隔离与分页回归保持。当前整份注释的OrderCloseScanIntegrationTest不计入本轮通过项。

2026-11-01：新增2项纯逻辑边界测试、1项真实MySQL/MockMvc集成测试，结合原创建1项、支付3项，共7项全部通过，无失败、错误或跳过。
集成数据使用独立随机用户/商品/订单，成功仅清理本次数据，失败保留现场；MockMvc使用模拟认证主体，不冒称真实网络JWT登录验收。自动关闭调度强制关闭。

在现有私有数据库环境就绪后运行（不要输出或提交凭据）：

```powershell
.\mvnw.cmd -o "-Dtest=OrderPageServiceTest,OrderPageIntegrationTest,OrderServiceTest,OrderPayServiceTest" "-Dmall.order.page.enabled=true" "-Dmall.order.close-schedule.enabled=false" test
```
