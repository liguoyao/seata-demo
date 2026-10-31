package com.acme.ecommerce.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalAuthentication extends OncePerRequestFilter {
  private final String token;

  public InternalAuthentication(@Value("${internal.token:}") String token) {
    this.token = token;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().startsWith("/internal/")) {
      String supplied = request.getHeader("X-Service-Token");
      if (token.isBlank()
          || supplied == null
          || !MessageDigest.isEqual(
              token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
        response.sendError(401);
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
