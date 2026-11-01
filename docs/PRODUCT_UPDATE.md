# 管理员商品资料编辑与上下架

PUT /products/{id}，使用管理员登录Token。每次同时提交名称和价格：

```json
{
  "name": "机械键盘新版",
  "price": 129.90
}
```

成功返回HTTP200/code0，data沿用ProductVo，包含当前库存。
name非空白且最多100字符；price至少0.01、最多10位整数和2位小数，与DECIMAL(12,2)一致。
请求只定义这两个可编辑字段，SKU、销售状态、库存及创建时间保持不变，更新时间由数据库维护。
相同信息重复提交仍成功；不存在返回HTTP404/code40401，非法参数HTTP400/code40001。

Service的update只用MyBatis-Plus设置name/price，随后在同一写事务内复用详情查询组装返回值。
0行更新通过回查区分不存在和同值未变化；若回查因库存缺失失败，名称/价格一起回滚。
request字段格式由DTO校验契约保证，直接调用Service的代码也须遵守该契约。
已有订单读取成交快照，新订单从数据库读取修改后的名称和价格。

## 商品上下架

PUT /products/{id}/status，使用管理员登录Token：

```json
{
  "status": "OFF_SALE"
}
```

ON_SALE为上架，OFF_SALE为下架；状态必填，非法枚举或参数返回40001，不存在返回40401。
成功返回HTTP200/code0及ProductVo。仅修改status，同值重复设置仍成功，不修改名称、价格、SKU或库存。
下架拒绝新的下单意图（新requestId，ORDER_CONFLICT/40901）；不自动取消旧订单或恢复库存。
相同requestId重试已存在订单仍返回原结果，旧订单可按原规则支付、取消或超时关闭；重新上架后可继续下单。
下单以读取商品时的状态裁决，已读取到ON_SALE的在途事务允许完成；本接口不承诺中断这些事务。
同值0行回查及返回组装失败回滚的处理，与资料编辑保持一致，事务日志事件为product_status_update。

## 验证

2026-11-02 02:02：新增上下架轻量2项，扩现有真实MySQL/MockMvc场景验证下架/重复下架/恢复上架、
字段与库存保持、下架新请求无新增订单或库存副作用、旧请求幂等重试、旧单支付、上架后恢复下单，
并验证非法状态/不存在和状态修改后的组装失败回滚。结合商品资料编辑及创建/详情回归，共15项通过，失败/错误/跳过均为0。

2026-11-02：商品编辑轻量3项、真实MySQL/MockMvc1项，商品创建5项及详情4项回归，共13项全部通过，无失败、错误或跳过。
真实库验证实际更新、字段/库存保持、同值重提、新旧订单快照、请求校验、商品不存在及返回组装失败后的真实回滚。
使用随机专用用户/商品/订单，成功后仅清理本次数据并回查；MockMvc使用模拟认证，不冒称真实网络登录验收。
既有商品写权限已静态核对正确，没有重复添加角色权限矩阵。

在本机私有数据库环境就绪后执行（不要打印或提交凭据）：

```powershell
.\mvnw.cmd -o "-Dtest=ProductStatusServiceTest,ProductUpdateServiceTest,ProductUpdateIntegrationTest,ProductCreateServiceTest,ProductDetailServiceTest" "-Dmall.product.update.enabled=true" "-Dmall.order.close-schedule.enabled=false" test
```

当前应用配置已启用自动关单，验收必须使用上述显式false，避免后台处理既有订单。
