package ru.truconf.proxydb.api;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import ru.truconf.proxydb.api.OutboxDtos.ErrorBody;
import ru.truconf.proxydb.api.OutboxDtos.ErrorDetail;
import ru.truconf.proxydb.api.OutboxDtos.FieldErrorDto;
import ru.truconf.proxydb.outbox.OutboxJobNotFoundException;
import ru.truconf.proxydb.truconf.TrueConfException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(OutboxJobNotFoundException.class)
  public ResponseEntity<ErrorBody> handleNotFound(OutboxJobNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), List.of());
  }

  @ExceptionHandler(ApiValidationException.class)
  public ResponseEntity<ErrorBody> handleApiValidation(ApiValidationException ex) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorBody> handleBeanValidation(MethodArgumentNotValidException ex) {
    List<FieldErrorDto> details = ex.getBindingResult().getAllErrors().stream()
        .map(this::toFieldError)
        .toList();
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", details);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorBody> handleUnreadableJson(HttpMessageNotReadableException ex) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body is not readable", List.of());
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ErrorBody> handleMissingPart(MissingServletRequestPartException ex) {
    return error(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Missing multipart part: " + ex.getRequestPartName(),
        List.of());
  }

  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ErrorBody> handleMultipart(MultipartException ex) {
    return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), List.of());
  }

  @ExceptionHandler(TrueConfException.class)
  public ResponseEntity<ErrorBody> handleTrueConf(TrueConfException ex) {
    HttpStatus status = ex.retryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
    return error(status, ex.code(), ex.getMessage(), List.of());
  }

  @ExceptionHandler({
      IllegalArgumentException.class,
      DataIntegrityViolationException.class
  })
  public ResponseEntity<ErrorBody> handleBadRequest(Exception ex) {
    return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), List.of());
  }

  private FieldErrorDto toFieldError(ObjectError error) {
    if (error instanceof FieldError fieldError) {
      return new FieldErrorDto(fieldError.getField(), fieldError.getDefaultMessage());
    }
    return new FieldErrorDto(error.getObjectName(), error.getDefaultMessage());
  }

  private ResponseEntity<ErrorBody> error(
      HttpStatus status,
      String code,
      String message,
      List<FieldErrorDto> details) {
    return ResponseEntity.status(status)
        .body(new ErrorBody(new ErrorDetail(code, message, details)));
  }
}
