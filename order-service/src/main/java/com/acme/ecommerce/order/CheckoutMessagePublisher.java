package com.acme.ecommerce.order;

import com.acme.ecommerce.order.model.TradeOrder;

public interface CheckoutMessagePublisher {
  void publish(TradeOrder order);
}
