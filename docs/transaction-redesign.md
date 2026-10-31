# Seata 全局消息分支与 RocketMQ 本地事务消息

本文件替代原 Outbox 扫描发布设计。版本和启动命令见 README。

## 下单短事务

CheckoutAtService.create 的 Seata @GlobalTransactional 包含三个动作：订单及 Saga 本地分支、同步库存预留分支、SeataMQProducer 支付创建消息分支。消息由 Seata 官方集成提供的 TCC 分支参与第二阶段提交/回滚，数据库仍使用 AT 分支。

订单初始化就是 PAYMENT_PREPARING，不再先写 AT_PENDING。Saga 在同一 AT 事务中创建；没有提交后激活窗口。Seata 发布器强制要求 XID，禁止无 XID 时退化成普通发送。

Seata 发布器内部持有官方工厂创建的生产者，不将它暴露成 DefaultMQProducer Bean，防止 RocketMQ Spring 的自动配置把两套生产者错误合并。全局消息分支和本地事务消息使用不同生产者组。

## 后续本地事务

LocalMessageTransactions 依次执行：

1. 在独立只读事务中读取聚合，规划本次输出事件。每次重试都使用新事务，避免 MyBatis 一级缓存保留竞争前的状态。
2. 为有输出的步骤创建唯一 transactionId，将 PREPARED、摘要和完整 MessageBatch 写入 message_transaction。
3. 使用 RocketMQTemplate.sendMessageInTransaction 发送半消息。未收到 SEND_OK 时不执行业务。
4. RocketMQ Spring 事务监听器调用 LocalMessageExecutor 的 REQUIRES_NEW 本地事务。它锁定准备记录，验证截止时间和摘要，再执行聚合行锁、CAS、Inbox 与业务变更。
5. BusinessEvents 校验实际输出与半消息中预先声明的输出完全一致；不一致则整个本地事务回滚，半消息回滚，重新规划。
6. 业务与 COMMITTED 决策原子提交后，监听器才返回 COMMIT。二次确认丢失由 Broker 回查恢复。

没有输出的步骤（例如首次保存渠道任务、重复事件无副作用）直接执行本地事务；若竞争后实际需要输出，校验会使本次事务回滚，再通过半消息重试。

一次步骤可能同时发退款和库存释放命令，因此使用一个事务消息批次，不能分别发送后只成功其中一个。消费者订阅 LOCAL_TX，并过滤属于本服务的事件；批次内每个事件都有稳定 eventId 和独立 Inbox 幂等。部分事件消费成功后 Broker 重投整个批次，其余事件继续处理。

## 回查与防迟到执行

COMMITTED 返回 COMMIT；ROLLED_BACK 返回 ROLLBACK。记录缺失、数据库不可用和未到期 PREPARED 返回 UNKNOWN。

准备状态到期后，回查使用 FOR UPDATE NOWAIT。活跃事务持有同一行锁时回查返回 UNKNOWN；只有无人执行时才能写入 ROLLED_BACK。执行器启动时也检查该记录与截止时间，因此不会在消息已判定回滚之后再提交业务。

回查只读取决策，不重新执行扣库存、退款或业务闭包。准备记录在发送半消息前持久化；数据库事务和决策在本地原子提交。进程退出导致未执行的半消息，最终通过回查和截止时间回滚，由原请求重试或业务恢复任务重新发起。

message_transaction 存储载荷供审计和回查摘要校验使用，不是 Outbox，没有扫描发布者。它不能替代渠道任务、Saga 恢复或财务对账。

## 保留的保护

Inbox/业务键幂等、订单与支付 @Version、库存终态互斥、稳定退款号、支付事实校验、渠道 UNKNOWN 恢复、DLQ 审计及指定消费组重放均保留。半消息发送失败会使对应本地步骤失败，因此 MQ 不可用时不会悄悄提交一个需要通知下游的业务状态。

## 升级与验证

先排空旧 Outbox、旧 Topic 和 AT_PENDING，再执行 deploy/mysql/migrations/001-transaction-messages.sql，创建 TRANSACTION 类型的新 Topic 并统一切换消费者订阅。不要将现有普通 Topic 原地混用事务消息，也不要直接丢弃旧记录。

常规测试模拟 RocketMQ Spring 的同步事务回调，使用真实 Spring/MyBatis-Plus/H2 事务验证本地原子性、状态机和回查。它不证明真实 Broker/Seata 可用。

integration-tests 需要真实 MySQL、Seata、RocketMQ，验证实际 HTTP XID、Seata 消息分支提交、远端库存回滚，以及发送半消息后异常时消息不得投递。当前机器没有 Docker，这些测试仅编译，尚未运行。

真实支付渠道验签、日账单对账和高可用故障演练仍按生产清单执行。
