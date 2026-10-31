# 本次验证记录：事务消息替换

执行日期：2026-10-30。

执行命令：`mvn -Pat-it -DskipITs verify`。

结果：全部六个模块构建成功，29 项测试通过，无失败。

| 模块 | 测试数 | 验证重点 |
|---|---:|---|
| ecommerce-common | 2 | RocketMQ Spring 事务发送 API、半消息 SEND_OK 确认 |
| order-service | 16 | Seata 无 XID 防降级、消息确认；支付/关闭仲裁、版本 CAS、批次原子性、回查、防迟到执行、并发规划和 Inbox 回滚 |
| stock-service | 4 | 预留幂等、终态不可复活、确认/释放并发、库存不足回滚 |
| payment-service | 7 | 验签拒绝、关闭先于创建、UNKNOWN 恢复、迟到回调、流水唯一性、事实校验、稳定退款号 |

本地测试使用真实 Spring/MyBatis-Plus/H2 事务，模拟 RocketMQ Spring 的事务回调。覆盖半消息发送失败时业务不提交、二次确认丢失后回查 COMMIT、未到期及不存在记录返回 UNKNOWN、过期记录 fencing、回查不得误回滚活跃事务、竞争后回滚错误半消息并重新规划，以及同一批次的退款和库存释放原子发布。

服务普通 JAR 与 `-exec.jar` 构建成功。独立 integration-tests 的 3 项真实验收测试已编译，通过 `-DskipITs` 明确跳过：

1. 真实 HTTP XID 传播，订单与库存提交后支付创建消息可消费。
2. 远端库存分支提交后价格校验失败，全局回滚数据库。
3. Seata 半消息发送后注入异常，订单与库存回滚，消息不得投递。

当前机器没有 Docker，未启动真实 MySQL、Seata TC 与 RocketMQ，不能用上述本地结果宣称真实 Broker 或全局消息分支已通过联调。

完整验收先启动 Compose，并执行 `deploy/Initialize-Messaging.ps1 -IncludeIntegrationTopic`，再执行 `mvn -Pat-it verify`。TC/Broker 掉电、主备切换、跨进程重启和真实支付渠道交互仍按生产清单演练。
