package com.acme.ecommerce.payment;

import java.math.BigDecimal;

public interface PaymentChannelGateway {
  enum State {
    PAID,
    UNPAID,
    CLOSED,
    NOT_FOUND,
    UNKNOWN
  }

  record QueryResult(
      State state,
      String channelCode,
      String merchantId,
      String paymentNo,
      String channelTradeNo,
      BigDecimal amount,
      String currency) {}

  enum RefundState {
    SUCCEEDED,
    PROCESSING,
    NOT_FOUND,
    UNKNOWN,
    FAILED
  }

  record RefundResult(
      RefundState state,
      String channelRefundNo,
      String paymentNo,
      String refundNo,
      BigDecimal amount,
      String currency) {}

  boolean verify(PaymentController.ChannelCallback callback);

  boolean create(String paymentNo, BigDecimal amount, String currency);

  QueryResult query(String paymentNo);

  QueryResult close(String paymentNo);

  RefundResult refund(String paymentNo, String refundNo, BigDecimal amount);

  RefundResult queryRefund(String paymentNo, String refundNo);
}
