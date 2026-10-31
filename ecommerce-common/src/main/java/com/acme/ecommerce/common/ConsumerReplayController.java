package com.acme.ecommerce.common;

import com.acme.ecommerce.common.mapper.MessageFailureMapper;
import com.acme.ecommerce.common.mapper.ReplayAuditMapper;
import com.acme.ecommerce.common.model.ReplayAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;

@RestController
@RequestMapping("/internal/messages")
public class ConsumerReplayController {
    private final MessageFailureMapper failures;
    private final ReplayAuditMapper audits;
    private final ApplicationContext context;
    private final ObjectMapper json;
    private final Clock clock;

    public ConsumerReplayController(MessageFailureMapper failures, ReplayAuditMapper audits, ApplicationContext context,
                                    ObjectMapper json, Clock clock) {
        this.failures = failures;
        this.audits = audits;
        this.context = context;
        this.json = json;
        this.clock = clock;
    }

    public record Replay(String operator, String reason) {
    }

    @PostMapping("/failures/{id}/replay")
    public void replay(@PathVariable long id, @RequestBody Replay request) throws Exception {
        if (request.operator() == null || request.operator().isBlank() || request.reason() == null || request.reason()
            .isBlank()) {
            throw new IllegalArgumentException("Operator and reason required");
        }
        var failure = failures.selectById(id);
        if (failure == null) {
            throw new IllegalArgumentException("Failure not found");
        }
        var decoded = EventMessages.decode(json, failure.payload);
        String local = context.getEnvironment().getProperty("spring.application.name") + "-events";
        if (!failure.consumerGroup.equals(local) && !failure.consumerGroup.equals("%DLQ%" + local)) {
            throw new IllegalArgumentException("Wrong consumer group");
        }
        var a = new ReplayAudit();
        a.eventId = decoded.get(0).eventId();
        a.operatorName = request.operator();
        a.reason = request.reason();
        a.createdAt = clock.instant();
        audits.insert(a);
        var listeners = context.getBeansOfType(TradingListener.class);
        if (listeners.size() != 1) {
            throw new IllegalStateException("Expected one local business consumer");
        }
        listeners.values().iterator().next().processPayload(failure.payload);
    }
}
