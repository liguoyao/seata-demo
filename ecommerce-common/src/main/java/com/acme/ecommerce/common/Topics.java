package com.acme.ecommerce.common;

public final class Topics {
  private Topics() {}

  public static final String STOCK_CONFIRM_FAILED = "STOCK_CONFIRM_FAILED";
  public static final String STOCK_CONFIRMED = "STOCK_CONFIRMED";
  public static final String STOCK_RELEASED = "STOCK_RELEASED";
  public static final String TRADING = "ECOMMERCE_TRADING_TX";
  public static final String STOCK_RESERVE_REQUESTED = "STOCK_RESERVE_REQUESTED";
  public static final String STOCK_RESERVED = "STOCK_RESERVED";
  public static final String STOCK_RESERVE_FAILED = "STOCK_RESERVE_FAILED";
  public static final String STOCK_CONFIRM_REQUESTED = "STOCK_CONFIRM_REQUESTED";
  public static final String STOCK_RELEASE_REQUESTED = "STOCK_RELEASE_REQUESTED";
  public static final String PAYMENT_CREATE_REQUESTED = "PAYMENT_CREATE_REQUESTED";
  public static final String PAYMENT_CREATED = "PAYMENT_CREATED";
  public static final String PAYMENT_CLOSE_REQUESTED = "PAYMENT_CLOSE_REQUESTED";
  public static final String PAYMENT_CLOSE_CONFIRMED = "PAYMENT_CLOSE_CONFIRMED";
  public static final String PAYMENT_SUCCEEDED = "PAYMENT_SUCCEEDED";
  public static final String REFUND_REQUESTED = "REFUND_REQUESTED";
  public static final String REFUND_SUCCEEDED = "REFUND_SUCCEEDED";
}
