package com.gateflow.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Problems problems;

    public ApiExceptionHandler(Problems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    public ProblemDetail domain(
            ApiException error, HttpServletRequest request, HttpServletResponse response) {
        if (error.retryAfterSeconds() > 0)
            response.setHeader("Retry-After", Long.toString(error.retryAfterSeconds()));
        return problems.create(request, error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(
            MethodArgumentNotValidException error, HttpServletRequest request) {
        ProblemDetail result =
                problems.create(
                        request,
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "Request validation failed");
        result.setProperty(
                "errors",
                error.getBindingResult().getFieldErrors().stream()
                        .map(
                                field ->
                                        Map.of(
                                                "field",
                                                field.getField(),
                                                "message",
                                                field.getDefaultMessage()))
                        .toList());
        return result;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail methodValidation(
            HandlerMethodValidationException error, HttpServletRequest request) {
        if (error.isForReturnValue())
            return problems.create(
                    request,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR",
                    "Response validation failed");
        if (!error.getBeanResults().isEmpty()) {
            var result =
                    problems.create(
                            request,
                            HttpStatus.BAD_REQUEST,
                            "VALIDATION_FAILED",
                            "Request validation failed");
            result.setProperty(
                    "errors",
                    error.getBeanResults().stream()
                            .flatMap(bean -> bean.getFieldErrors().stream())
                            .map(
                                    field ->
                                            Map.of(
                                                    "field",
                                                    field.getField(),
                                                    "message",
                                                    field.getDefaultMessage()))
                            .toList());
            return result;
        }
        return parameter(request);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        ConstraintViolationException.class,
        MissingRequestHeaderException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    public ProblemDetail parameter(HttpServletRequest request) {
        return problems.create(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER",
                "Invalid path or pagination parameter");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail malformed(HttpServletRequest request) {
        return problems.create(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_JSON",
                "Malformed or unsupported JSON fields");
    }

    @ExceptionHandler({
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class,
        NoResourceFoundException.class
    })
    public ProblemDetail framework(
            Exception error, HttpServletRequest request, HttpServletResponse response) {
        ErrorResponse framework = (ErrorResponse) error;
        framework
                .getHeaders()
                .forEach(
                        (name, values) -> values.forEach(value -> response.addHeader(name, value)));
        HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
        String detail =
                switch (status) {
                    case NOT_FOUND -> "Resource not found";
                    case METHOD_NOT_ALLOWED -> "HTTP method is not supported";
                    case UNSUPPORTED_MEDIA_TYPE -> "Media type is not supported";
                    default -> "Request is not supported";
                };
        return problems.create(request, status, "HTTP_ERROR", detail);
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public void unacceptable(HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        // An unacceptable Accept header must not prevent serializing the error itself.
        problems.write(
                request,
                response,
                HttpStatus.NOT_ACCEPTABLE,
                "HTTP_ERROR",
                "Response media type is not supported");
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(HttpServletRequest request) {
        return problems.create(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "Service storage is unavailable");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception error, HttpServletRequest request) {
        LOG.error(
                "request_failure request_id={} exception_type={}",
                request.getAttribute(RequestIdFilter.ATTRIBUTE),
                error.getClass().getSimpleName());
        return problems.create(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred");
    }
}
