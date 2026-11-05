package com.acme.ecommerce.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Component
public class ChannelIsolation {
    private final ThreadPoolExecutor pool;
    private final long timeoutMs;

    public ChannelIsolation(@Value("${payment.channel-concurrency:4}") int concurrency,
                            @Value("${payment.channel-timeout-ms:10000}") long timeoutMs) {
        if (concurrency < 1 || timeoutMs < 1 || timeoutMs >= 30000) {
            throw new IllegalArgumentException("Invalid channel isolation limits");
        }
        this.timeoutMs = timeoutMs;
        pool = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            var t = new Thread(r, "payment-channel");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> T call(Callable<T> action) throws Exception {
        Future<T> future = pool.submit(action);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw ex;
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Channel call failed", ex.getCause());
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        pool.shutdownNow();
    }
}
