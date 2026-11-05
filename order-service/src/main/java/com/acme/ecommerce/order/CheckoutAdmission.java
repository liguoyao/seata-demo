package com.acme.ecommerce.order;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
/** Instance admission budget; deployment capacity is replicas times this limit. */
@Component
public class CheckoutAdmission extends OncePerRequestFilter {
 private final Semaphore permits;
 public CheckoutAdmission(@Value("${order.max-concurrent-checkouts:16}") int limit) {
  if(limit<1) throw new IllegalArgumentException("checkout limit must be positive");
  permits=new Semaphore(limit);
 }
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
  if(!"POST".equals(req.getMethod()) || !"/api/orders".equals(req.getRequestURI())) { chain.doFilter(req,res); return; }
  if(!permits.tryAcquire()) { res.setHeader("Retry-After","1"); res.sendError(429,"CHECKOUT_CAPACITY_EXCEEDED"); return; }
  try { chain.doFilter(req,res); } finally { permits.release(); }
 }
}
