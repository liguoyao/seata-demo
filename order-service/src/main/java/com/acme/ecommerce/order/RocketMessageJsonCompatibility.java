package com.acme.ecommerce.order;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;
import java.util.Collection;
import org.apache.rocketmq.common.message.Message;

/**
 * RocketMQ 5.x Message 与 Fastjson 的两处兼容处理：
 *
 * <ol>
 *   <li>keys: 字段是 String，但存在 setKeys(Collection) 重载，Fastjson 反序列化时选中该重载并要求 JSON 数组
 *       （"field keys expect '[', but string"）——排除该 setter，让 Fastjson 走 setKeys(String)。
 *       KEYS 同时保存在 properties 中，因此无信息丢失。</li>
 *   <li>deliverTimeMs: 没有 backing 字段，getter 直接 Long.parseLong(TIMER_DELIVER_MS 属性)，属性缺失即抛
 *       NumberFormatException——排除该 getter（无 setter 调用需求，properties 已完整序列化，无损）。</li>
 * </ol>
 */
public final class RocketMessageJsonCompatibility {
  private RocketMessageJsonCompatibility() {}

  abstract static class MessageMixin {
    @JSONField(deserialize = false)
    abstract void setKeys(Collection<String> keys);

    @JSONField(serialize = false)
    abstract long getDeliverTimeMs();
  }

  public static void register() {
    JSON.addMixInAnnotations(Message.class, MessageMixin.class);
  }
}
