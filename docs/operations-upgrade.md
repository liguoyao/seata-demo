# 运行升级说明

先应用 002 订单库和 003 支付库迁移。部署文件与测试目录已取消 Git 忽略，避免交付遗漏。

库存事件新增 reservationNo、skuId、quantity；先升级库存生产者并排空旧消息，再升级订单消费者。旧消息缺少事实字段会拒绝处理，必须从原始预留记录核对后修复，禁止跳过校验。退款号遵循同样顺序。

渠道并发由 payment.channel-concurrency 控制，默认 4；单次等待 payment.channel-timeout-ms 默认 10000。无排队隔离池耗尽后快速失败，操作继续以 UNKNOWN 重试。超时不表示渠道失败，固定支付号和退款号必须由真实渠道保证幂等。忽略中断的请求继续占用隔离槽，避免无界线程；真实 HTTP 适配器仍必须设置连接、读取超时。本仓库未提供具体渠道协议，因此未伪造真实适配器。

POST /internal/reconciliation 使用已有 X-Service-Token 保护。请求 channel、merchant、completeHistory=true、rows；每行 kind=PAYMENT/REFUND、businessNo、channelReference、amount、currency。输入必须为该商户完整成功流水，最多十万行。输出 runId，可 GET /internal/reconciliation/{runId} 查看 CHANNEL_ONLY、LOCAL_ONLY、FACT_MISMATCH、DUPLICATE_CHANNEL。差异只登记，不自动退款。实时导入存在在途差异，必须在稳定快照时核查；此实现不是具有结算日水位的日账单自动下载器，人工审核及关闭差异工作流尚未实现。

Prometheus 抓取 /actuator/prometheus，加载 deploy/monitoring/trading-alerts.yml。须由运维配置真实 Alertmanager 接收人和路由，内部指标端口限制在监控网络。监控表缺失导致指标采集失败，先迁移后部署。

高可用模板 deploy/kubernetes/service-template.yaml 为三副本、跨节点分散、滚动发布及 PDB；每个服务替换 APP_NAME/IMAGE 并配置独立数据库密钥。仅为应用层模板，不能证明基础设施 HA。MySQL 使用已验收的高可用写端点；TC 共用持久化事务存储和一致事务组；Broker 需验证复制、刷盘和故障切换。集群内 STOCK_BASE_URL 指向库存 Service；注册中心和 Broker 通告地址必须在所有 Pod 可达。禁止采用本机默认地址。

发布前实测：杀单 Pod 后订单持续恢复；TC 切换不误提交；Broker 切换不丢已确认事件；数据库切换后无重复扣款；备份在隔离环境恢复并与渠道核账。记录实际 RTO/RPO、资金差异和未决事务数量。未执行这些演练前不得标记 HA 验收通过。
