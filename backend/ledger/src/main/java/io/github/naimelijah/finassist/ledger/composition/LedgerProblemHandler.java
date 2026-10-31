package io.github.naimelijah.finassist.ledger.composition;

import io.github.naimelijah.finassist.ledger.shared.CurrencyMismatchException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DatabindException;

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
 * <p>Every problem it answers carries {@code type}, {@code title}, {@code status} and {@code code}:
 *
 * <ul>
 *   <li>a failure Spring MVC raised is coded from its status: 400 {@code MALFORMED_REQUEST}, 404
 *       {@code NOT_FOUND}, 405 {@code METHOD_NOT_ALLOWED}, 406 {@code NOT_ACCEPTABLE}, 409 {@code
 *       CONFLICT}, 413 {@code CONTENT_TOO_LARGE} and 415 {@code UNSUPPORTED_MEDIA_TYPE}. A malformed
 *       request also lists what was rejected under {@code errors}, each entry a {@code field} and a
 *       {@code message}: every bean-validation failure; a body member Jackson refused, by its path
 *       ({@code entries[1].amount.currency}); a path or query parameter of the wrong type, or a
 *       required one left out, by its name; and unparseable JSON or a missing body as {@code body}.
 *       Every one shares a fixed detail, never Spring's, which differs by the exception that carried
 *       the failure. For the last three the message is fixed as well, never Jackson's or Spring's,
 *       which can quote what was sent (ADR-019);
 *   <li>{@link IllegalArgumentException} is 422 {@code RULE_VIOLATED}, with the exception's message as
 *       the detail. It signals that a well-formed request broke a rule: the domain raises it for its own
 *       invariants, and an inbound adapter for a rule about the request itself, such as a period that
 *       does not start before it ends. Its messages hold no secrets, as the ledger holds none (ADR-019).
 *       It is not the domain's only refusal: {@code IllegalTransitionException} and {@code
 *       IllegalPlanTransitionException} extend {@link RuntimeException}, so each is left to its own
 *       context's advice. Accounting's maps the first and planning's the second, each to 409;
 *   <li>a {@link MultipartException}, a body declared as multipart that cannot be parsed, is 400 {@code
 *       MALFORMED_REQUEST}. Spring MVC parses multipart before it chooses a route, a {@code GET}'s
 *       included, and nothing in the ledger takes multipart, so a failure to parse it is the request's. A
 *       body over the upload limit is the narrower {@link MaxUploadSizeExceededException}, which the
 *       inherited handler still answers 413;
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

    /**
     * The title of a problem whose status has no {@link HttpStatus} constant. Such a status has no reason
     * phrase for {@link ProblemDetail#getTitle()} to fall back on, and the problem mix-in's {@code
     * NON_EMPTY} would then drop {@code title}, which every other problem carries (ADR-028).
     */
    private static final String UNNAMED_STATUS_TITLE = "Unexpected Status";

    private static final String UNPARSEABLE_MULTIPART_DETAIL =
            "The request declares a multipart body that cannot be parsed; the ledger reads JSON only.";

    /** The assistant's detail for the same problem, so the two services read alike. */
    private static final String MALFORMED_REQUEST_DETAIL =
            "The request could not be read; each entry in errors names why.";

    /**
     * The {@code field} of an entry that blames the whole body, as the assistant's does when it cannot
     * take a body whole. Unparseable JSON has no member to name.
     */
    private static final String BODY_FIELD = "body";

    // Each reason is fixed, never the parser's or the converter's message, which quote what was sent.
    private static final String INVALID_VALUE = "is not a valid value";
    private static final String REQUIRED_VALUE = "is required";
    private static final String NOT_JSON = "is not valid JSON";
    private static final String UNREADABLE = "could not be read";

    /**
     * The code for each status Spring MVC can raise on its own. Keyed by status value, so a status with no
     * {@link HttpStatus} constant needs no special case.
     */
    private static final Map<Integer, String> CODES_BY_STATUS = Map.of(
            HttpStatus.BAD_REQUEST.value(), Problems.MALFORMED_REQUEST,
            HttpStatus.NOT_FOUND.value(), Problems.NOT_FOUND,
            HttpStatus.METHOD_NOT_ALLOWED.value(), Problems.METHOD_NOT_ALLOWED,
            HttpStatus.NOT_ACCEPTABLE.value(), Problems.NOT_ACCEPTABLE,
            HttpStatus.CONFLICT.value(), Problems.CONFLICT,
            HttpStatus.CONTENT_TOO_LARGE.value(), Problems.CONTENT_TOO_LARGE,
            HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), Problems.UNSUPPORTED_MEDIA_TYPE,
            HttpStatus.UNAUTHORIZED.value(), Problems.UNAUTHENTICATED,
            HttpStatus.FORBIDDEN.value(), Problems.FORBIDDEN);

    /** A well-formed request broke a rule, which the domain or an inbound adapter enforces. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Object> handleRuleViolation(IllegalArgumentException exception, WebRequest request) {
        return respond(
                exception,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage()),
                Problems.RULE_VIOLATED,
                request);
    }

    /**
     * A body declared as multipart that cannot be parsed, such as one whose type names no boundary. The
     * detail is fixed, because the parser's message describes its own internals rather than the request.
     */
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Object> handleUnparseableMultipart(MultipartException exception, WebRequest request) {
        return respond(
                exception,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, UNPARSEABLE_MULTIPART_DETAIL),
                Problems.MALFORMED_REQUEST,
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
     * A denial raised from inside a handler — a future method-level permission check, say — must
     * never fall through to {@link #handleUnexpected}: {@code LedgerSecurityConfiguration}'s own
     * {@code ProblemAccessDeniedHandler} answers every denial the filter chain itself raises, which
     * is every one today, but this handler is what stops a denial thrown by a controller becoming a
     * 500 instead of a 403 the day one exists.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException exception, WebRequest request) {
        return respond(
                exception,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.FORBIDDEN, "This token does not grant the permission this request needs."),
                Problems.FORBIDDEN,
                request);
    }

    /**
     * The security filter chain answers every authentication failure itself, through {@code
     * ProblemAuthenticationEntryPoint}, before a controller is ever reached. This handler exists for
     * the same reason {@link #handleAccessDenied} does: so that if an {@link AuthenticationException}
     * is ever raised from inside one, the catch-all below cannot turn it into a 500.
     */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleUnauthenticated(AuthenticationException exception, WebRequest request) {
        return respond(
                exception,
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "This request needs a valid bearer token."),
                Problems.UNAUTHENTICATED,
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

    /**
     * A body that cannot be read into the handler's parameter. Jackson's message quotes the input, so
     * neither it nor the converter's detail, which repeats it, reaches the wire.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return malformed(exception, List.of(unreadableBody(exception.getCause())), headers, status, request);
    }

    /**
     * A path or query parameter that cannot be converted to its type. Spring's own detail quotes the
     * value, so it is replaced.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return malformed(
                exception, List.of(violation(exception.getPropertyName(), INVALID_VALUE)), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return malformed(
                exception, List.of(violation(exception.getParameterName(), REQUIRED_VALUE)), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return malformed(
                exception,
                exception.getBindingResult().getAllErrors().stream()
                        .map(error -> violation(error, error.getObjectName()))
                        .toList(),
                headers,
                status,
                request);
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
        if (status.is4xxClientError()) {
            return malformed(
                    exception,
                    exception.getParameterValidationResults().stream()
                            .flatMap(result -> result.getResolvableErrors().stream()
                                    .map(error -> violation(
                                            error, result.getMethodParameter().getParameterName())))
                            .toList(),
                    headers,
                    status,
                    request);
        }
        return handleExceptionInternal(
                exception,
                exception.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale()),
                headers,
                status,
                request);
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
     * type}, {@code title} or {@code code} already set is kept; a 5xx detail is always replaced.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getType() == null) {
                problem.setType(Problems.BLANK_TYPE);
            }
            if (problem.getTitle() == null) {
                problem.setTitle(UNNAMED_STATUS_TITLE);
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
     * A malformed request, answered with the one fixed detail every malformed request shares and an entry
     * for each input that was rejected, so a client reads why from {@code errors} alone.
     */
    private ResponseEntity<Object> malformed(
            Exception exception,
            List<Map<String, String>> violations,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, MALFORMED_REQUEST_DETAIL);
        problem.setProperty(Problems.ERRORS_MEMBER, violations);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    /**
     * The entry for a body that could not be read, from what the converter wrapped. A value Jackson
     * refused is named by its path, or as {@link #BODY_FIELD} when the body itself is that value; text
     * that is not JSON, or no body at all, names {@link #BODY_FIELD}.
     *
     * <p>An {@link InputCoercionException}, a whole number too large for its member, is a refused value
     * like a {@link DatabindException}, though in Jackson 3 it extends {@link StreamReadException}, so it is
     * checked first. Any other {@link StreamReadException} means the text is not JSON at all, whatever
     * member the parser had reached.
     */
    private static Map<String, String> unreadableBody(Throwable cause) {
        if (cause instanceof JacksonException rejection && refusesAValue(rejection)) {
            List<JacksonException.Reference> path = rejection.getPath();
            return violation(path.isEmpty() ? BODY_FIELD : wirePath(path), INVALID_VALUE);
        }
        if (cause instanceof StreamReadException) {
            return violation(BODY_FIELD, NOT_JSON);
        }
        return violation(BODY_FIELD, UNREADABLE);
    }

    /** Whether Jackson read the JSON but refused a value in it, rather than failing to read the text. */
    private static boolean refusesAValue(JacksonException failure) {
        return failure instanceof DatabindException || failure instanceof InputCoercionException;
    }

    /**
     * Jackson's path in the form bean validation names a field, {@code entries[1].amount.currency}, so a
     * client finds a member under one name whichever of the two refused it. Jackson builds each reference
     * from either a member's wire name or an element's index.
     */
    private static String wirePath(List<JacksonException.Reference> path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference reference : path) {
            String name = reference.getPropertyName();
            if (name == null) {
                field.append('[').append(reference.getIndex()).append(']');
            } else {
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(name);
            }
        }
        return field.toString();
    }

    /**
     * A status outside the table, a 5xx or a 4xx alike, is {@code INTERNAL_ERROR}: the ledger never means to
     * send one. A code is never derived from an {@link HttpStatus} constant's name, because a code is
     * contract once served and Spring renames constants: Spring 7 already renamed 413 and 422.
     */
    private static String codeFor(HttpStatusCode status) {
        return CODES_BY_STATUS.getOrDefault(status.value(), Problems.INTERNAL_ERROR);
    }

    /**
     * One {@code errors} entry. A {@link LinkedHashMap}, so {@code field} precedes {@code message} on the
     * wire, and because Spring may have no message to give where {@code Map.of} refuses a null.
     */
    private static Map<String, String> violation(MessageSourceResolvable error, String subject) {
        return violation(error instanceof FieldError field ? field.getField() : subject, error.getDefaultMessage());
    }

    private static Map<String, String> violation(String field, String message) {
        Map<String, String> violation = new LinkedHashMap<>();
        violation.put(Problems.FIELD_MEMBER, field);
        violation.put(Problems.MESSAGE_MEMBER, message);
        return violation;
    }
}
