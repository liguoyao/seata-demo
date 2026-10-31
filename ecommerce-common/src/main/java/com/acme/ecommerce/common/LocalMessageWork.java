package com.acme.ecommerce.common;

public final class LocalMessageWork {
  final MessageBatch batch;
  final String payloadHash;
  final Runnable action;
  RuntimeException failure;
  boolean completed;

  public LocalMessageWork(MessageBatch batch, String payloadHash, Runnable action) {
    this.batch = batch;
    this.payloadHash = payloadHash;
    this.action = action;
  }
}
