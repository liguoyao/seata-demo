package com.acme.ecommerce.common;

import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;

public interface TransactionMessageTransport {
  RocketMQLocalTransactionState send(Message<String> message, LocalMessageWork work);
}
