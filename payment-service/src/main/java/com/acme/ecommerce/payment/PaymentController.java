package com.acme.ecommerce.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService service;
    private final PaymentChannelGateway gateway;
    private final com.acme.ecommerce.common.MessageFailureStore failures;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public PaymentController(PaymentService service, PaymentChannelGateway gateway,
                             com.acme.ecommerce.common.MessageFailureStore failures,
                             com.fasterxml.jackson.databind.ObjectMapper json) {
        this.service = service;
        this.gateway = gateway;
        this.failures = failures;
        this.json = json;
    }

    public record ChannelCallback(String channelCode, String merchantId, String channelEventId, String paymentNo,
                                  String orderNo, BigDecimal amount, String currency, String channelTradeNo,
                                  String signature) {
    }

    @PostMapping("/channel-callback")
    public ResponseEntity<?> callback(@RequestBody ChannelCallback callback) {
        if (!gateway.verify(callback)) {
            return ResponseEntity.status(401).body("INVALID_CHANNEL_SIGNATURE");
        }
        try {
            service.callback(callback);
            return ResponseEntity.ok("SUCCESS");
        } catch (RuntimeException ex) {
            try {
                failures.record("channel-callback", callback.channelEventId(), json.writeValueAsString(callback), 1,
                    ex);
            } catch (Exception auditError) {
                ex.addSuppressed(auditError);
            }
            throw ex;
        }
    }
}
