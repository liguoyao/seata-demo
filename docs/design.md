-
# 交易平台整体架构梳理

## 0. 全局鸟瞰

三个业务服务 + 一个公共模块，围绕**一条订单的生命周期**协作：

```
用户 ──HTTP──► order-service ──Seata AT──► stock-service（同步预留）
                    │
                    ├─Seata MQ 半消息分支──────► RocketMQ (ECOMMERCE_TRADING_TX)
                    │
                    ▼ 之后所有步骤走「本地事务消息」(RocketMQ 事务消息)
             payment-service ◄────┐
             stock-service ◄──────┼── 事件在三个服务间流转，各自驱动状态机
             order-service ◄─────┘
                    ▲
支付渠道(外部) ──HTTP 回调──► payment-service
```

**两条消息通道、两套生产者组，刻意分离**（README 71 行）：

| 通道 | 生产者 | 用途 | 消息何时可见 |
|---|---|---|---|
| Seata MQ 半消息（tag = 事件类型，如 `PAYMENT_CREATE_REQUESTED`） | `order-service-seata-tx-producer` | 只在下单那条 Seata 全局事务里发 | TC 全局提交后 |
| RocketMQ 事务消息（tag = `LOCAL_TX`） | 各服务 `*-local-tx-producer` | 之后所有 Saga 步骤 | 本地事务提交后 |

Topic 都是同一个 `ECOMMERCE_TRADING_TX`（TRANSACTION 类型），消费者用 tag 过滤（各 `*EventConsumer` 的 `selectorExpression`）。

---

## 1. order-service（端口 8081）

### HTTP 请求

| 请求 | 位置 | 逻辑 |
|---|---|---|
| `POST /api/orders`（Header: `Idempotency-Key`） | `OrderController.create` 29–53 行 | 校验参数 → 算 `requestHash` → 按 `(user_id, idempotency_key)` 查旧单：命中且 hash 相同则幂等返回 202；hash 不同返回 409 `IDEMPOTENCY_KEY_CONFLICT`；否则进 `CheckoutAtService.create`。重复插入靠唯一约束 `uk_orders_0` 兜底 |
| `GET /api/orders/{orderNo}` | 64–67 行 | 查单 |
| `POST /api/orders/{orderNo}/cancel` | 69–73 行 | 调 `flow.cancel`（见下，条件不满足时不产生任何变更） |

**下单 Seata 全局事务**（`CheckoutAtService.create` 31–59 行，`@GlobalTransactional("checkout-reserve")`）：

1. 写 `orders` 行，状态 `PAYMENT_PREPARING`，`paymentDeadline = now + 900s`，记录 `globalXid`；
2. **同步 HTTP** 调 stock 的 `/internal/stock/reserve`（`StockClient`），核对返回的金额币种，不一致抛 `PRICE_MISMATCH` → 全局回滚；
3. `flow.initialize(o)` 写 `order_saga` 初始行（AT 分支，MANDATORY 事务）；
4. `publisher.publish(o)`：`SeataCheckoutMessagePublisher` 发 **Seata 半消息** `PAYMENT_CREATE_REQUESTED`，无 XID 直接抛错、绝不退化成普通发送（46–48 行）。

四个动作在一个全局事务里：**要么订单+库存预留+Saga+支付命令全生效，要么全消失**。

### 数据库表（`orders` 库）

| 表 | 作用 | 关键列/约束 |
|---|---|---|
| `orders` | 订单主表（聚合根） | `status`、`version`（乐观锁）、`payment_deadline`、`uk_orders_0(user_id,idempotency_key)`、`idx_timeout(status,payment_deadline)` |
| `order_saga` | Saga 进度 | `current_step`、`status`(RUNNING/SUCCEEDED/COMPENSATING/COMPENSATED/ATTENTION_REQUIRED)、`retry_count`、`next_retry_time`、`stock_released`、`refund_completed`，`idx_saga_retry(status,next_retry_time)` |
| `undo_log` | Seata AT 回滚日志 | `ux_undo_log(xid,branch_id)` |
| `message_transaction` | 本地事务消息的 PREPARED/COMMITTED 决策 + 完整 payload（供 broker 回查与审计） | |
| `inbox_event` | 消费幂等 | `uk(consumer_name,event_id)` |
| `message_failure` | DLQ 审计 | |
| `replay_audit` | 人工重放审计 | |

### MQ 消费（`OrderEventConsumer`，组 `order-service-events`）

订阅 `PAYMENT_CREATED || PAYMENT_SUCCEEDED || PAYMENT_CLOSE_CONFIRMED || REFUND_SUCCEEDED || STOCK_CONFIRMED || STOCK_RELEASED || STOCK_CONFIRM_FAILED`，最多重试 16 次后进 DLQ。每个事件进 `OrderFlow.consume` → plan/apply 机制 → 驱动订单状态机（上一轮已详述，不重复）。

### MQ 发布

全部经 `LocalMessageTransactions`（tag `LOCAL_TX`），按状态机在事务里发出：`PAYMENT_CREATE_REQUESTED`、`PAYMENT_CLOSE_REQUESTED`、`STOCK_CONFIRM_REQUESTED`、`STOCK_RELEASE_REQUESTED`、`REFUND_REQUESTED`。关键：**`REFUND_REQUESTED` 与 `STOCK_RELEASE_REQUESTED` 可在同一批次**（`OrderFlow.applyRecover` 78–85 行）。

### 定时任务

**`OrderTimeoutScheduler.scan`**（`@Scheduled` 每 `order.recover-ms`，默认 1 秒）：查 `order_saga` 里 `next_retry_time <= now` 且状态非 `SUCCEEDED/COMPENSATED` 的前 100 条，逐条 `flow.cancel(no)`（超时关单判定）+ `flow.recover(no)`（卡步重催）。异常只 warn 不中断（38–40 行）。**这是整个系统自愈的心脏。**

---

## 2. stock-service（端口 8082）

### HTTP 请求

只有一个内部端点：`POST /internal/stock/reserve`（`StockController` 19–25 行）。**强制要求请求方带着 Seata XID**（`RootContext.inGlobalTransaction()`），否则拒绝——库存预留只发生在下单全局事务里。

`StockService.reserve`（67–106 行，`@GlobalLock`）：

- `FOR UPDATE` 锁 `sku_stock` 行；已存在同单预约且参数一致 → 幂等返回价格（78–86 行）；
- 库存不足抛 `INSUFFICIENT_STOCK` → 全局回滚，订单也不存在；
- 否则 `availableQty -= qty`、`reservedQty += qty`，插 `stock_reservation`（状态 `RESERVED`），返回 `单价×数量`。

### 数据库表

| 表 | 作用 |
|---|---|
| `sku_stock` | 每个 SKU 的三桶数量：`available_qty` / `reserved_qty` / `sold_qty` + `version`（乐观锁），CHECK 约束禁止负数。预置 SKU 1001（149.50 CNY，10000 件） |
| `stock_reservation` | 每单一条：`status ∈ {RESERVED, CONFIRMED, RELEASED}` |
| `undo_log` | Seata AT |
| 其余公共表同上 | |

**不变式**：`reserve` 加 `available`/`reserved`；`confirm` 把 `reserved` 转记到 `sold`；`release` 把 `reserved` 还回 `available`。`applyConsume` 141–143 行还有一道防御：`reservedQty < quantity` 直接抛 `Stock invariant violated`。

### MQ 消费（组 `stock-service-events`）

只订阅两类**命令**：`STOCK_CONFIRM_REQUESTED`、`STOCK_RELEASE_REQUESTED`。`applyConsume`（110–163 行）：

- 幂等靠 `inbox.firstDelivery` + 预约行状态；
- **确认遇到已释放的预约** → 发 `STOCK_CONFIRM_FAILED`（reason=ALREADY_RELEASED），不直接报错——订单侧据此转退款；
- 中间态冲突（既不是 RESERVED 也不是目标终态）→ 抛异常让 MQ 重试；
- 成功 → 更新库存桶 + 预约终态 → 发 `STOCK_CONFIRMED` 或 `STOCK_RELEASED` 回执。

### MQ 发布

`STOCK_CONFIRMED`、`STOCK_RELEASED`、`STOCK_CONFIRM_FAILED`（都走 LOCAL_TX 事务消息）。

### 定时任务

**没有**。stock 是纯被动的：所有恢复都由 order 侧 `recover` 重发命令驱动。

---

## 3. payment-service（端口 8083）

### HTTP 请求

`POST /api/payments/channel-callback`（`PaymentController` 33–50 行）：外部渠道异步通知。先 `gateway.verify(callback)` 验签，401 拒签；处理异常时把原始回调记入 `message_failure` 再抛出（43–47 行），防丢单。

`PaymentService.applyCallback`（252–280 行）做四重防重放/防串单：

1. `verify`：渠道号、商户号、支付单号、金额、币种、渠道流水号逐项与库里的 `payment_order` 比对；
2. 渠道事件幂等：`event_key = sha256(channel|merchant|channelEventId)`，已存在且 payload hash 不同 → `CHANNEL_EVENT_CONFLICT`；相同则直接返回（重放通知）；
3. `paid()`：已是 SUCCESS 且渠道流水号不同 → `MULTIPLE_CHANNEL_TRADES`（防一单多付）；
4. 首次成功 → 状态 `SUCCESS` + 记录渠道流水号 → 发 `PAYMENT_SUCCEEDED`。

### 数据库表

| 表 | 作用 |
|---|---|
| `payment_order` | 支付单：`status ∈ {INIT, PAYING, CLOSE_PENDING, CLOSED, SUCCESS}`、`close_requested`、`version`、`uk(payment_no)`、`uk(channel_code,merchant_id,channel_trade_no)`（防一单多付的第二道闸） |
| `refund_order` | 退款单：固定退款号 `R{orderNo}`、`uk(payment_no)`（一笔支付最多一笔退款） |
| `channel_operation` | **渠道任务队列**：`type ∈ {CREATE, QUERY, CLOSE, REFUND}`、`status ∈ {NEW, RETRY, RUNNING, UNKNOWN, DONE}`、`attempts`、`next_retry_time`、租约列 `lease_token/lease_until` |
| `payment_channel_event` | 渠道通知幂等 |
| 其余公共表同上（payment 库无 `undo_log`？有——schema 第 1 行注释说明 AT 用于 order/stock；payment 库里没有 undo_log，它不参与 AT） | 确认：payment schema 中**没有** `undo_log`，payment 不进 Seata 全局事务 |

### MQ 消费（组 `payment-service-events`）

订阅三条**命令**：`PAYMENT_CREATE_REQUESTED`、`PAYMENT_CLOSE_REQUESTED`、`REFUND_REQUESTED`。`applyConsume`（148–228 行）：

- 支付单不存在则**从事件里建单**（151–167 行），并校验 `paymentNo == "P" + orderNo`、金额为正——事件本身就是建单事实；
- 已存在则核对 paymentNo/金额/币种，不一致抛 `Conflicting payment intent`；
- `CREATE`：已成功→补发 `PAYMENT_SUCCEEDED`；已 PAYING→补发 `PAYMENT_CREATED`；否则建 `CREATE` 渠道任务；
- `CLOSE`：`closeRequested=true`；已付→`PAYMENT_SUCCEEDED`（迟到的钱，交给订单侧转退款）；已关→`PAYMENT_CLOSE_CONFIRMED`；否则转 `CLOSE_PENDING` + 建 `CLOSE` 任务；
- `REFUND`：要求支付必须已 `SUCCESS`（199–201 行，**没有确认的资金不允许退款**）；建/复用退款单 `R{orderNo}`，未成功则建 `REFUND` 任务。

**注意 payment 侧也有 plan/apply**（`planConsume` 31–63 行），和订单侧完全同一套机制——预判要补发什么回执，apply 里逐字核验。

### 渠道任务执行（异步、租约制、带主动查询兜底）

### 定时任务

**`ChannelWorker.run`**（`@Scheduled` 每 `payment.worker-ms` 默认 1 秒，每轮最多 20 个任务）：

1. `claim()`（`PaymentService` 308–323 行）：`LIMIT 1 FOR UPDATE SKIP LOCKED` 抢任务——多实例互不阻塞；条件为（状态 NEW/RETRY/UNKNOWN 且到重试时间）或（RUNNING 且租约过期），抢到置 RUNNING、发新 `leaseToken`、租约 60 秒；
2. 按 `op.type` 调渠道网关：`CREATE` 建单、`QUERY` 查单、`CLOSE` 关单（**先决条件：CREATE 任务已 DONE 才允许真关渠道**，43–48 行；从未建过单则直接本地判 CLOSED，不惊动渠道）、`REFUND` 先 `queryRefund`，NOT_FOUND 才真正发起退款（**幂等设计：先查后做**）；
3. `finish` → `applyFinish`（338–400 行，同样走 plan/apply）：核对租约 `owned()`（防旧持有者复活覆盖新结果）→ 核对退款四要素 → 更新状态并按结果发 `PAYMENT_CREATED / PAYMENT_SUCCEEDED / PAYMENT_CLOSE_CONFIRMED / REFUND_SUCCEEDED` → 任务 DONE 或 UNKNOWN；
4. `CREATE` 成功后顺手建一个 `QUERY` 任务（354 行）——**主动轮询兜底渠道回调丢失**；
5. 网关调用抛异常 → `failed`（403–414 行）：任务转 UNKNOWN，**指数退避** `2^attempts` 秒（封顶 300s）；attempts ≥ 100 时 error 日志告警。

---

## 4. 公共基础设施（ecommerce-common）

| 组件 | 职责 |
|---|---|
| `LocalMessageTransactions` | plan/apply 执行器：4 次重试、PREPARED 落库、半消息、回查决策 |
| `LocalMessageExecutor` | broker 回调入口：`FOR UPDATE` 锁 `message_transaction`、验摘要/截止期、REQUIRES_NEW 执行业务、置 COMMITTED |
| `BusinessEvents` | ThreadLocal 预言队列 + 逐字校验，`PlanChanged` |
| `Inbox` | 消费幂等（payloadHash 冲突检测） |
| `TradingListener` | 消费者基类：解析单信封或批次 → 过滤本组关心的类型 → 逐个 `consume`；失败记 `message_failure` 后抛异常让 MQ 重投 |
| `EventMessages.decode` | payload 可能是单 `EventEnvelope`（Seata 半消息）或 `MessageBatch`（本地事务消息，多事件一事务） |
| `ConsumerReplayController` | `POST /internal/messages/failures/{id}/replay`：人工重放 DLQ 审计里的事件，强制要求 operator+reason，写 `replay_audit`，只允许重放本消费组的消息（45–47 行） |
| `*DeadLetterListener` | 三个服务各一个，把 `%DLQ%` 消息原文存进 `message_failure`，存失败也重试 |
| `MessageFailureStore` | DLQ/回调失败的统一审计写入 |

---

## 5. 把所有东西串起来：三条典型时间线

### A. 正常成交
```
1. POST /api/orders ──Seata全局事务──► orders(PAYMENT_PREPARING) + stock_reserve(RESERVED)
                                    + order_saga(RUNNING) + Seata半消息[PAYMENT_CREATE_REQUESTED]
   └─TC 全局提交 → 半消息转正
2. payment 消费 CREATE_REQUESTED → 建 payment_order(INIT) + channel_operation(CREATE)
3. ChannelWorker: gateway.create 成功 → PAYING + 发 PAYMENT_CREATED + 建 QUERY 任务
4. 用户付款 → 渠道回调 channel-callback → payment SUCCESS + 发 PAYMENT_SUCCEEDED
5. order 消费 PAYMENT_SUCCEEDED → 校验金额 → PAID_PENDING_STOCK_CONFIRM + 发 STOCK_CONFIRM_REQUESTED
6. stock 消费 CONFIRM_REQUESTED → reserved→sold → 发 STOCK_CONFIRMED
7. order 消费 STOCK_CONFIRMED → COMPLETED，saga=SUCCEEDED
```

### B. 超时关单（900 秒未付）
```
1. OrderTimeoutScheduler 扫到 → flow.cancel → CANCEL_REQUESTED + 发 PAYMENT_CLOSE_REQUESTED
2. payment: CLOSE_PENDING + 建 CLOSE 任务 → ChannelWorker: 确认 CREATE 已 DONE → gateway.close
   → CLOSED + 发 PAYMENT_CLOSE_CONFIRMED
3. order → CANCEL_PENDING_STOCK_RELEASE + 发 STOCK_RELEASE_REQUESTED
4. stock: reserved→available → 发 STOCK_RELEASED
5. order → CANCELLED，saga=COMPENSATED
```

### C. 迟到支付 / 库存确认失败 → 退款
```
若第 2 步后用户才付款：回调把 payment 置 SUCCESS + 发 PAYMENT_SUCCEEDED
→ order 消费：状态已是 CANCEL_* 分支 → 不回退，转 REFUND_REQUIRED + 发 REFUND_REQUESTED（同批次可带 STOCK_RELEASE_REQUESTED）
→ payment: 校验 SUCCESS 资金 → 退款单 R{orderNo} + REFUND 任务 → ChannelWorker queryRefund/refund
→ REFUND_SUCCEEDED；stock → STOCK_RELEASED
→ order 双条件齐（refundCompleted && stockReleased）→ REFUNDED，saga=COMPENSATED
```

任何一步消息丢失/宕机，各服务靠 `order_saga` / `channel_operation` 的 `next_retry_time` + 两个定时任务**从落库状态反推该重发什么**，整个系统向前收敛。

---

## 6. 各服务对照速查

| 维度 | order-service | stock-service | payment-service |
|---|---|---|---|
| HTTP | `POST /api/orders`、`GET /{no}`、`POST /{no}/cancel` | `POST /internal/stock/reserve` | `POST /api/payments/channel-callback` |
| Seata | 发起方（@GlobalTransactional） | 参与方（AT 分支，reserve） | 不参与 |
| 业务表 | orders、order_saga | sku_stock、stock_reservation | payment_order、refund_order、channel_operation、payment_channel_event |
| 消费命令 | 7 种事实事件 | STOCK_CONFIRM/RELEASE_REQUESTED | PAYMENT_CREATE/CLOSE_REQUESTED、REFUND_REQUESTED |
| 发布事件 | 5 种命令 | 3 种回执 | 4 种回执 |
| 定时任务 | OrderTimeoutScheduler（1s） | 无 | ChannelWorker（1s，租约+退避） |
| 自愈载体 | order_saga.next_retry_time | 被动 | channel_operation.next_retry_time |
| 幂等键 | (user_id,idempotency_key)、Inbox、@Version | 预约行状态、Inbox、@Version | Inbox、paymentNo、channel_event、(channel,merchant,tradeNo)、租约 |

一个值得注意的不对称设计：**stock 没有定时任务、payment 的定时任务只管渠道交互**——所有"业务该推进了"的判断都集中在 order 侧，由 `OrderFlow.planRecover` 统一决策；而 payment 的 `ChannelWorker` 只负责"把外部渠道这个不可靠世界对齐到数据库"。故障域划分清晰：order 管编排自愈，payment 管渠道适配，stock 管纯账务。