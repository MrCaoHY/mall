# mall 日志约定与取消接口

## 取消订单接口

`POST /orders/{orderId}/cancel`，请求头携带 `Authorization: Bearer <token>`，无请求体。
orderId为订单主键，buyerId从认证主体获取；前端不传买家ID、状态或取消时间。
成功和重复取消均返回HTTP 200，响应为现有 `ApiResponse<OrderVo>`；不存在或不属于当前用户返回40402，其他不可取消状态返回40901。
取消状态与全部库存恢复在同一事务；只在条件更新影响1行时恢复库存，重复取消不能再次恢复。

## 事件与级别

- INFO：每次HTTP完成（http_access）、注册/登录成功、幂等重试、事务提交/回滚。业务冲突和参数错误也保留INFO结果，避免误报系统故障。
- WARN：认证或权限拒绝、无效Token、事务结果未知。
- ERROR：未知异常和数据完整性故障。未知异常堆栈集中在MVC异常出口；逃逸过滤器链的异常在请求过滤器记录。
- DEBUG：商品查询摘要及无真实事务时的事务结果观察；生产默认关闭。

每条日志包含时间、级别、logger、线程；请求内日志通过MDC附加requestId和已认证userId。
event用于搜索事件，operation/entityId用于识别业务对象。事务提交日志在afterCompletion中记录，方法返回前不记录“提交成功”。
requestId由本服务生成，并通过响应头X-Request-ID返回；它不是下单requestId，订单日志中的orderRequestId才是客户端下单意图号，也不是分布式traceId。
当前接口是同步Servlet；未来增加异步任务或跨服务调用时，需要单独传播上下文或接入链路追踪。

## 敏感信息与日志注入

不记录请求体、响应体、Authorization、Token、密码、密码Hash、JWT密钥、原始Claims、用户名或任意DTO/实体的toString。
只记录路由模板，不记录原始路径、query、搜索词及原始异常消息；未知异常保留异常类型、代码行和cause链，消息统一移除，防止JDBC异常带出SQL实参。
MyBatis显式关闭SQL日志；请勿在生产启用Spring Security/Web/SQL TRACE或DEBUG、打印配置对象或对整个请求做AOP参数日志。
开发用CreateKey测试会输出新密钥，仅限受控本机，不能在共享CI执行；不把它改造成运行时日志。

## 配置与部署

默认本地控制台为可读格式，文件为Logstash JSON；路径默认logs/mall.log，可用LOG_FILE指定。
文件按天且单片达到50MB时压缩轮转，保留14天，归档总上限1GB；当前活跃文件及清理时机不包含在硬性磁盘配额保证内。
可配置LOG_MAX_FILE_SIZE、LOG_MAX_HISTORY、LOG_TOTAL_SIZE_CAP；这些是本项目初始值，应按流量、故障排查和实际保留要求调整。
使用SPRING_PROFILES_ACTIVE=prod后，控制台也输出JSON。示例启动参数：`--spring.profiles.active=prod`。
普通本地排查可通过MALL_LOG_LEVEL=DEBUG开启应用查询摘要，prod配置固定应用INFO；不要因此开启SQL实参日志。
日志文件已被Git忽略。项目使用自有数据库登录与JWT，因此排除默认内存用户自动配置，避免框架生成并打印开发密码；原安全链权限规则保留。
部署时设置日志目录最小权限，按应用/实例隔离LOG_FILE，并配置采集、告警和磁盘监控；容器使用挂载目录，避免日志随实例销毁丢失。
日志采集平台、告警、访问控制和分布式追踪尚未部署，本次仅完成应用侧基础。日志不是订单状态或财务审计的可靠存储，不能代替数据库业务记录。

## 静态核对与验收边界

此次不运行编译或测试。待本机验证：取消一次库存恢复、重复取消库存不变、库存恢复失败事务回滚，以及401/403/业务异常/未知异常均带X-Request-ID且MDC不串请求。
检查logs/mall.log为逐行JSON、prod控制台为JSON，日志中没有密码/Token/密钥/SQL实参。轮转、采集和实际吞吐需要部署环境验收。

参考：[Spring Boot日志](https://docs.spring.io/spring-boot/reference/features/logging.html)、[OWASP日志指南](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)。
