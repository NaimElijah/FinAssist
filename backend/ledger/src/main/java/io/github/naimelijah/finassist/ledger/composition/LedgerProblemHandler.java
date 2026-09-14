package io.github.naimelijah.finassist.ledger.composition;

import io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Answers every failure no context claims as an RFC 9457 problem with a stable {@code code} (ADR-028).
 *
 * <p>It runs last. The advices that arrive with each context's controllers sit at the highest
 * precedence and map that context's typed exceptions to specific codes, and whatever none of them
 * claims arrives here. Extending {@link
 * ResponseEntityExceptionHandler} keeps Spring MVC's own exceptions at their own statuses: an unknown
 * route stays 404, a form post 415 and an unreadable body 400, where a bare handler for {@link
 * Exception} would make each of them a 500. {@code spring.mvc.problemdetails.enabled} stays unset,
 * because it would register a second handler of this kind.
 *
 * <p>Every problem it answers carries {@code type} and {@code code}:
 *
 * <ul>
 *   <li>a failure Spring MVC raised is coded from its status: 400 {@code MALFORMED_REQUEST}, 404
 *       {@code NOT_FOUND}, 405 {@code METHOD_NOT_ALLOWED}, 406 {@code NOT_ACCEPTABLE} and 415 {@code
 *       UNSUPPORTED_MEDIA_TYPE}. Bean-validation failures also list each rejection under {@code
 *       errors};
 *   <li>{@link IllegalArgumentException} is 422 {@code RULE_VIOLATED}, with the exception's message as
 *       the detail. It signals that a well-formed request broke a rule: the domain raises it for its own
 *       invariants, and an inbound adapter for a rule about the request itself, such as a period that
 *       does not start before it ends. Its messages hold no secrets, as the ledger holds none (ADR-019).
 *       It is not the domain's only refusal: {@code IllegalTransitionException} and {@code
 *       IllegalPlanTransitionException} extend {@link RuntimeException}, so each is left to its own
 *       context's advice. Accounting's maps the first; the second reaches this handler as a 500 until
 *       planning's advice maps it;
 *   <li>{@link CurrencyMismatchException} is 422 {@code CURRENCY_MISMATCH}. It is mapped once here
 *       because all three contexts throw it;
 *   <li>anything else is 500 {@code INTERNAL_ERROR}.
 * </ul>
 *
 * <p>Every 5xx, whoever raised it, is logged at ERROR with its exception and answered with a generic
 * detail. An exception message is among the likeliest carriers of internals and data, so none reaches
 * the wire; the log line carries the trace id that finds it.
 *
 * <p>Component-scanned rather than built in {@link LedgerConfiguration}, for the reason ADR-028 gives
 * for controllers: a {@code @WebMvcTest} slice loads scanned advice and skips scanned configuration.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class LedgerProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerProblemHandler.class);

    private static final String INTERNAL_ERROR_DETAIL = "The ledger failed to handle this request.";

    /** ADR-028's names for the statuses Spring MVC raises on its own. */
    private static final Map<HttpStatus, String> CODES_BY_STATUS = Map.of(
            HttpStatus.BAD_REQUEST, Problems.MALFORMED_REQUEST,
            HttpStatus.NOT_FOUND, Problems.NOT_FOUND,
            HttpStatus.METHOD_NOT_ALLOWED, Problems.METHOD_NOT_ALLOWED,
            HttpStatus.NOT_ACCEPTABLE, Problems.NOT_ACCEPTABLE,
            HttpStatus.UNSUPPORTED_MEDIA_TYPE, Problems.UNSUPPORTED_MEDIA_TYPE);

    /** A well-formed request broke a rule, which the domain or an inbound adapter enforces. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> handleRuleViolation(IllegalArgumentException exception, WebRequest request) {
        return respond(
                exception,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage()),
                Problems.RULE_VIOLATED,
                request);
    }

    /** The request would combine two currencies, which has no answer without a rate (ADR-012). */
    @ExceptionHandler(CurrencyMismatchException.class)
    ResponseEntity<Object> handleCurrencyMismatch(CurrencyMismatchException exception, WebRequest request) {
        return respond(
                exception,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage()),
                Problems.CURRENCY_MISMATCH,
                request);
    }

    /**
     * Everything nothing else claimed. The problem is built without the exception's message, so it
     * never holds one to leak.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        return respond(
                exception, ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR), Problems.INTERNAL_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = exception.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        problem.setProperty(
                Problems.ERRORS_MEMBER,
                exception.getBindingResult().getAllErrors().stream()
                        .map(error -> violation(error, error.getObjectName()))
                        .toList());
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /**
     * Raised instead of the above when a constraint sits on a parameter rather than inside a body, and
     * also when a handler's own return value breaks a constraint.
     *
     * <p>{@code errors} is listed for a 4xx only. A broken return-value constraint is the ledger's fault
     * and Spring answers it 500; its messages describe the ledger's own output, so they stay off the wire
     * with the rest of a 5xx's detail.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = exception.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        if (status.is4xxClientError()) {
            problem.setProperty(
                    Problems.ERRORS_MEMBER,
                    exception.getParameterValidationResults().stream()
                            .flatMap(result -> result.getResolvableErrors().stream()
                                    .map(error -> violation(
                                            error, result.getMethodParameter().getParameterName())))
                            .toList());
        }
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /** Every problem passes through here, this class's and the framework's alike. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            LOG.error("Answered {} with a generic detail", statusCode.value(), exception);
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    /**
     * The last step before a problem is written, and the one place that guarantees its shape. A {@code
     * type} or {@code code} already set is kept; a 5xx detail is always replaced.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getType() == null) {
                problem.setType(Problems.BLANK_TYPE);
            }
            if (statusCode.is5xxServerError()) {
                problem.setDetail(INTERNAL_ERROR_DETAIL);
            }
            Map<String, Object> members = problem.getProperties();
            if (members == null || !members.containsKey(Problems.CODE_MEMBER)) {
                problem.setProperty(Problems.CODE_MEMBER, codeFor(statusCode));
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> respond(
            Exception exception, ProblemDetail problem, String code, WebRequest request) {
        problem.setProperty(Problems.CODE_MEMBER, code);
        return handleExceptionInternal(
                exception, problem, new HttpHeaders(), HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    /**
     * A status outside ADR-028's table is named by its standard reason, such as {@code CONFLICT}. A 5xx,
     * and a status with no standard name, is {@code INTERNAL_ERROR}: the ledger means to send neither.
     */
    private static String codeFor(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        if (known == null || known.is5xxServerError()) {
            return Problems.INTERNAL_ERROR;
        }
        return CODES_BY_STATUS.getOrDefault(known, known.name());
    }

    /**
     * One {@code errors} entry. A {@link LinkedHashMap}, so {@code field} precedes {@code message} on the
     * wire, and because Spring may have no message to give where {@code Map.of} refuses a null.
     */
    private static Map<String, String> violation(MessageSourceResolvable error, String subject) {
        Map<String, String> violation = new LinkedHashMap<>();
        violation.put(Problems.FIELD_MEMBER, error instanceof FieldError field ? field.getField() : subject);
        violation.put(Problems.MESSAGE_MEMBER, error.getDefaultMessage());
        return violation;
    }
}
