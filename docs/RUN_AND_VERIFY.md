# 轻量订单库存系统（Mall）：核心链路重现与使用说明

本文档汇总了商城系统阶段一（纯单体数据库与核心交易一致性）的完整业务能力、环境配置要求、端到端接口时序演示以及自动化回归验证指南。

---

## 一、 环境依赖与配置说明

### 1. 运行环境要求
* **JDK 版本**：Java 21 (LTS)
* **数据库**：MySQL 8.x
* **构建工具**：Maven 3.9+（仓库内已内置 `mvnw.cmd` / `./mvnw`）

### 2. 关键配置与环境变量
系统核心配置位于 [`src/main/resources/application.yaml`](../src/main/resources/application.yaml)，支持通过环境变量灵活覆盖：

| 配置项 | 环境变量 | 默认值 / 示例 | 说明 |
| :--- | :--- | :--- | :--- |
| `spring.datasource.url` | `DB_HOST`, `DB_PORT` | `jdbc:mysql://${DB_HOST:192.168.5.29}:${DB_PORT:3306}/mall?...` | 数据库连接地址 |
| `spring.datasource.username`| `DB_USERNAME` | `root` | 数据库用户名 |
| `spring.datasource.password`| `DB_PASSWORD` | `mysql` | 数据库密码（请勿硬编码进仓库） |
| `jwt.secret` | `JWT_SECRET` | *(必需由环境注入)* | HMAC-SHA256 签名私钥（测试环境由测试基类动态生成） |
| `jwt.expiration` | `JWT_EXPIRATION` | `2h` | Token 有效期 |
| `mall.order.scan.enabled` | 无 | `false` | **超时关单定时调度开关**。默认为 `false`，生产按需开启，测试环境显式控制，避免后台后台扫描污染单测现场。 |

---

## 二、 核心交易全链路端到端重现（HTTP / RESTful）

所有写操作及敏感查询均需在 HTTP Header 携带 JWT：
`Authorization: Bearer <TOKEN>`

### 步骤 1：身份认证（用户注册与登录）
* **接口**：`POST /users/login` 或 `POST /users/register`
* **说明**：支持 `ADMIN`（管理员）与 `USER`（普通买家）两类角色。买家 ID 强绑定自 Token 验签上下文，杜绝横向越权。

### 步骤 2：管理员发布商品与初始化库存
* **接口**：`POST /products/create` （需 ADMIN 角色）
* **请求体**：
  ```json
  {
    "sku": "KEYBOARD-RGB-001",
    "name": "机械键盘青轴版",
    "price": 199.00,
    "initialStock": 10
  }
  ```
* **业务保证**：商品表与库存表在同一事务中原子插入，初始状态置为 `ON_SALE`。

### 步骤 3：管理员维护商品（改价与上下架）
* **修改名称与价格**：`PUT /products/{id}`
  ```json
  {
    "name": "机械键盘青轴新版",
    "price": 189.00
  }
  ```
  *说明*：历史已创建订单快照隔离不变，后续新订单按新资料计费。
* **商品下架**：`PUT /products/{id}/status`
  ```json
  {
    "status": "OFF_SALE"
  }
  ```
  *说明*：下架后立即拒绝新请求号下单（返回 `40901 ORDER_CONFLICT`）；已存在的历史未支付订单仍可按原生命周期正常支付或取消。

### 步骤 4：买家下单与即时扣库存
* **接口**：`POST /orders` （需 USER 角色）
* **请求体**：
  ```json
  {
    "requestId": "9c1b3f2e-4a6c-48b2-b1e8-71e224e20001",
    "items": [
      {
        "productId": 1,
        "quantity": 2
      }
    ]
  }
  ```
* **核心一致性保障**：
  1. **防超卖**：采用条件更新 `UPDATE product_inventories SET stock = stock - ? WHERE product_id = ? AND stock >= ?`，单条 SQL 抢占行锁扣减，受影响行数为 0 立即全事务回滚。
  2. **幂等防重**：相同 `requestId` 重复提交时，服务端捕获唯一索引冲突，回查原单并原样返回已有 `OrderVo`，不重复扣库存。

### 步骤 5：模拟支付与操作流水
* **接口**：`POST /orders/{orderId}/pay`
* **核心机制**：
  - 只有订单归属于当前买家且状态为 `PENDING_PAYMENT` 时允许支付；
  - 支付状态流转（变为 `PAID`）与 `OrderOperationLog`（`PAY` 操作流水）在同一事务强一致提交；
  - 支付成功不再次扣库存。

### 步骤 6：买家主动取消订单与恢复库存
* **接口**：`POST /orders/{orderId}/cancel`
* **核心机制**：
  - 状态通过 `WHERE id = ? AND status = 'PENDING_PAYMENT'` 原子流转为 `CANCELLED`；
  - 状态变更成功后，同事务内回补商品库存 `stock = stock + quantity`，并写入 `CANCEL` 审计流水。

### 步骤 7：超时关单与 Keyset 游标批量扫描
* **执行入口**：`OrderCloseScanService.closeExpiredOrdersBatch()`（配合 `@Scheduled` 定时触发）
* **抗漏扫设计（Keyset Pagination）**：
  - 采用复合游标 `(expires_at, id)` 全序推进，彻底解决传统 `OFFSET` 分页在数据变动时的“分页漂移漏扫”；
  - 引入“固定周期截止”（Pinned `cycleCutoff`），整轮扫描锁定截止时间，遇到坏数据（毒丸订单）依然平滑跳过继续后移，空批回卷重置；
  - 采用 `@Transactional(propagation = Propagation.NEVER)`，单单隔离独立事务，某笔失败不影响整批。

### 步骤 8：买家订单列表与详情快照查询
* **订单列表**：`GET /orders?page=1&size=10&status=PENDING_PAYMENT`
  - 仅按 Token 身份查询本人订单，支持按状态过滤与主键+时间稳定排序；
* **订单详情**：`GET /orders/{orderId}`
  - 返回主表及明细成交快照（下单时的商品名称、单价、数量快照）；
  - 查询非本人订单或不存在订单统一返回 `40402 ORDER_NOT_FOUND`，防止订单 ID 探测遍历。

---

## 三、 自动化回归验证指令

在本地数据库就绪后，可通过 Maven Wrapper 一键执行端到端自动化测试验证（包含 MockMvc、服务单元测试以及真实 MySQL 事务隔离性测试）：

### 1. 全套业务与集成测试回归指令
```powershell
.\mvnw.cmd test "-Dtest=ProductCreateServiceTest,ProductDetailServiceTest,ProductStatusServiceTest,ProductUpdateServiceTest,ProductUpdateIntegrationTest,OrderCreateServiceTest,OrderPayServiceTest,OrderCancelIntegrationTest,OrderCloseScanServiceTest,OrderCloseBatchIntegrationTest,OrderPageServiceTest,OrderPageIntegrationTest" "-Dmall.product.update.enabled=true" "-Dmall.order.close-schedule.enabled=false"
```

### 2. 测试安全约束
* 所有真实库测试均使用随机生成的专用数据（随机 SKU、随机订单号与买家），并在 `finally` 块中精准清理；
* 测试中动态注入随机密钥，不产生配置硬编码；
* 测试中显式声明 `-Dmall.order.close-schedule.enabled=false`，杜绝后台扫描线程干扰断言现场。

---

## 四、 阶段总结与后续规划

* **阶段一成果（已闭环）**：完成单体应用下商品发布、上下架、改价快照、下单防超卖、幂等防重、主动取消恢复库存、并发状态屏障、复合游标批量超时关单及本人权限分页详情查询。
* **阶段二规划（待启动）**：
  1. **Redis 缓存实战**：商品资料 Cache-Aside 读写、事务提交后双写一致性失效、防穿透/防击穿/防雪崩设计；
  2. **RabbitMQ 延迟队列**：30 分钟延迟消息到期自动关单，结合现有数据库游标实现“消息削峰 + 数据库轮询兜底”双保险架构。
