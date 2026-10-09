package com.example.ragpoc.web;

import com.example.ragpoc.document.DocumentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Turns expected failures into responses that do not leak information.
 *
 * <p>A document id belonging to another tenant produces the same 404 as a
 * non-existent one. A 403 would confirm the document exists, which is a small
 * but real leak of another organisation's activity.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(DocumentNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String notFound(DocumentNotFoundException e) {
    log.debug("Not found: {}", e.getMessage());
    return "error/404";
  }

  /**
   * A cross-tenant storage key is a signal rather than a typo.
   *
   * <p>It should be unreachable: keys come from tenant-scoped rows. If it fires,
   * something has gone wrong in a way worth investigating, so it is answered
   * with a plain 404 and logged at error level.
   */
  @ExceptionHandler(SecurityException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String securityException(SecurityException e) {
    log.error("Refused a cross-tenant access attempt: {}", e.getMessage());
    return "error/404";
  }
}
