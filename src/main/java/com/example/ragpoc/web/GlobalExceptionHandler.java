package com.example.ragpoc.web;

import com.example.ragpoc.document.DocumentNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Turns expected failures into responses that do not leak information: a
 * document id belonging to another cid produces the same 404 as a non-existent
 * one, because a 403 would confirm it exists.
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(DocumentNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String notFound(DocumentNotFoundException e) {
    log.debug("Not found: {}", e.getMessage());
    return "error/404";
  }

  /**
   * A cross-cid storage key is a signal rather than a typo. It should be
   * unreachable; if it fires, something went wrong worth investigating.
   */
  @ExceptionHandler(SecurityException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public String securityException(SecurityException e) {
    log.error("Refused a cross-cid access attempt: {}", e.getMessage());
    return "error/404";
  }
}
