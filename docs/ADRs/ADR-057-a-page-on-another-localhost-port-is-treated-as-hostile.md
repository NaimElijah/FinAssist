# ADR-057: A page on another `localhost` port is treated as hostile

- **Status:** Accepted
- **Date:** 2026-10-30

Extends [ADR-030](ADR-030-oidc-authentication-through-a-bff-gateway.md), which gives the browser
a session cookie and a script-readable CSRF cookie and names the renamed cookies' reason: cookies
ignore ports. It does not say what follows from that for a page that is not FinAssist. It also
extends [ADR-029](ADR-029-web-client-stack.md), which chose the web client's stack on Vite
([ADR-009](ADR-009-react-vite-spa-for-the-web-client.md)) and says nothing of who else may reach
the servers that run it, or of what the client shows when a sign-out fails.

## Context

**Cookies ignore ports, so a page on another `localhost` port is same-site with the app**
([#340](https://github.com/NaimElijah/FinAssist/issues/340)). ADR-030 uses the fact to rename the
session and CSRF cookies. The threat model is one machine and one owner (ADR-030), so the party in
view is no remote attacker: it is a different application on the same machine, such as any other
development server. The gateway therefore accepts the CSRF token from its header alone, and the
other controls below follow from the same premise. Until now the gateway's CSRF handler also
accepted the token from a request parameter, which is not a custom header.

**The login entry is public and creates a session.** Every request to it that arrives without a
session makes one to hold the authorization request, which lives for the configured 30 minutes.
Nothing bounded which requests could do so, or how many sessions there were.

**An upstream service could set state for the app's whole origin and name its software.** The
relay copied every upstream response header but the hop-by-hop ones. That includes a `Set-Cookie`,
an order to clear site data, an HSTS policy and a CORS grant, each of which changes what the browser
does with the whole origin, and which is the gateway's alone to set. It includes a `Server`, which
names the service's software.

**Four smaller gaps sat in the same place.**

- **The sign-in failure log line** carried the OAuth2 error code as it arrived. The callback's
  `error` query parameter becomes that code unchanged, so its text was chosen by whoever sent the
  callback.
- **The firewall's refusals** (an encoded dot segment, an empty segment) reached Boot's error
  body, which has no `code` member and echoes the caller's path. The ledger answers a malformed
  request `MALFORMED_REQUEST`.
- **A failed sign-out was silent.** The page left the owner believing a session was closed that
  was still open.
- **Nothing kept markup out of the HTML parser.** ADR-030's premise is that a script injected into
  the page can make requests while the session lasts. The web client had no rule against handing a
  string to the parser as markup.

**The web client's two servers are held to the app's own origin.** The dev server answers
`/__open-in-editor` only for a request whose `Sec-Fetch-Site` is `same-origin`, so only the app's
own page can use it. Neither server grants CORS to another origin, and both refuse to be framed.

## Decision

**The gateway treats a request from a page on another `localhost` port as not the app's.**

- **The CSRF token comes from its header only.** `HeaderOnlyCsrfTokenRequestHandler` reads
  `X-FINASSIST-XSRF-TOKEN` and nothing else, and compares it as it arrives, because the web client
  sends the cookie's raw value. It keeps `csrf().spa()`'s eager cookie write.
- **The login entry starts a sign-in only from a top-level navigation.**
  `LoginEntryNavigationFilter` refuses a request when `Sec-Fetch-Mode` is present and is not
  `navigate`, or `Sec-Fetch-Dest` is present and is not `document`, before any session exists. A
  navigation into a frame is a navigation, but not of the window, and is not a sign-in. A header
  that is absent passes: only a browser sends Fetch Metadata, and a client that does not is not a
  page.
- **The refusal is 403 `FORBIDDEN`, not the entry point's 401.** The entry is public, so a 401
  would tell a client that credentials could change the answer. The gateway widens `FORBIDDEN` to
  mean a refusal no credential would change: a session that lacks a role, or a browser request to
  the public login entry that is not a top-level navigation. `LoginEntryNavigationFilter` writes
  the second through `GatewayProblems`, with a detail of its own, and not through the
  access-denied handler, whose detail speaks of a session that does not yet exist. The ledger's
  and the assistant's `FORBIDDEN` keep their meaning: a genuine identity, denied by the
  permission its role does not hold.
- **A session made for a started sign-in idles out after 15 minutes.**
  `PreSignInAuthorizationRequestRepository` gives only a session it causes to exist a limit of
  `PRE_SIGN_IN_IDLE_LIMIT`, 15 minutes: long enough for a first sign-in, which changes the
  password and enrols TOTP at Keycloak's pages. The sign-in success handler restores the
  idle limit every new session gets, which the container takes from
  `server.servlet.session.timeout`: 30 minutes.
- **A service's origin-wide answer headers are stripped from relayed answers.** Both relay routes
  carry a `RemoveResponseHeader` for each of `Set-Cookie`, `Server`, `Clear-Site-Data`,
  `Strict-Transport-Security` and seven `Access-Control-*` headers: `-Allow-Origin`,
  `-Allow-Credentials`, `-Allow-Methods`, `-Allow-Headers`, `-Allow-Private-Network`,
  `-Expose-Headers` and `-Max-Age`. Each is named on its own, because the filter removes one name at
  a time. A cookie the gateway writes itself, such as a fresh CSRF cookie, is set on the servlet
  response and is unaffected.
- **The sign-in failure log names only a registered code.** `SignInFailureHandler` logs an
  OAuth2 error code if it is one of the 29 in `REGISTERED_ERROR_CODES` (RFC 6749, OpenID Connect
  Core and the codes Spring Security raises itself), and `unrecognised` otherwise. A failure that is
  not an OAuth2 one still logs `unknown`.
- **A firewall rejection is a 400 `MALFORMED_REQUEST` problem.** `ProblemRequestRejectedHandler`
  answers it in the gateway's problem shape with a fixed detail, after marking the observation as
  Spring's default handler does.

**The web client's two servers refuse the same page.**

- **`/__open-in-editor` answers only a request marked `Sec-Fetch-Site: same-origin`.** Any other
  request, including one with no such header, is refused 403 before Vite's own middleware.
- **Both `vite dev` and `vite preview` send `Content-Security-Policy: frame-ancestors 'none'`** on
  what they serve themselves, and set `cors: false`. The full policy for a built bundle stays
  [#165](https://github.com/NaimElijah/FinAssist/issues/165)'s.
- **A failed sign-out shows an alert.** `UserMenu` announces that sign-out failed and that the
  session is still open, and clears it when a retry succeeds. The alert takes a line of its own, so
  it wraps below the name and the button on a narrow screen. A failure returns focus to Sign out,
  which the pending button had dropped, but only while focus rests on the page body. The alert is
  keyed on a failure counter, so a second failure mounts it afresh and is announced again.
- **No file hands markup to the HTML parser.** ESLint's `no-restricted-syntax` fails
  `npm run lint` on `dangerouslySetInnerHTML` or `srcDoc`, an `innerHTML` or `outerHTML`
  assignment, a call to `insertAdjacentHTML`, `createContextualFragment`, `setHTMLUnsafe` or
  `parseFromString`, and `document.write` or `document.writeln`. Reading `innerHTML` stays allowed.
  `frontend/web/CLAUDE.md` lists the forms and what the rule does not catch.

## Consequences

**Makes easy**

- **No request parameter, form field or header-less request can carry the CSRF token.** The one
  accepted carrier is a custom header, which a cross-origin page cannot send without a CORS
  preflight that no listener on the app's origin answers.
- **A subresource or frame request cannot make the gateway hold a session.** And a sign-in that
  is started and abandoned holds its memory for 15 minutes, not 30.
- **A service cannot set a cookie, clear site data, set HSTS or grant CORS for the app's origin,
  or name its software, through the relay.**
- **A log line cannot carry text a caller chose,** because the code is looked up, never echoed.
- **A foreign page cannot frame the app or read what the dev server serves** through CORS.

**Makes hard**

- **A first sign-in that takes over 15 minutes fails at the callback** and is started again. A
  first sign-in changes the password and enrols TOTP; a slow one is the case this limit costs.
- **A new Spring Security release can add an OAuth2 error code** that the 29 do not list. It is
  logged as `unrecognised` until the list is read again, which loses the code, not the failure.
- **A client that sends no Fetch Metadata is not stopped by the navigation rule.** A `curl` or a
  script on the machine can still start a sign-in; only the 15-minute limit bounds what it holds.
- **The CSRF defence now leans on no listener of the app's origin answering a credentialed CORS
  preflight.** The dev server's `cors: false` is part of it; the gateway has no CORS configuration
  at all (ADR-030).
- **Two Vite behaviours are pinned by tests that read the dev server.** A Vite release that moves
  where `/__open-in-editor` is mounted, or what `server.headers` covers, turns them red.

**Accepted cost**

- **A session Keycloak ends keeps relaying for up to about four minutes.** The gateway learns of
  it only when it refreshes the 5-minute access token, which it does as the token nears expiry,
  and `/bff/me` answers from the session until then. Back-channel logout would close the gap and
  is not adopted. The lag belongs beside the role staleness
  [#159](https://github.com/NaimElijah/FinAssist/issues/159) already records.

## Alternatives considered

- **Serve the CSRF token in `/bff/me`'s body instead of a cookie.** Rejected: it changes the
  gateway's published contract, makes the token mutable client state, and adds a stale-token path
  when the session rotates. **Revisit when** any listener on the SPA's origin answers a credentialed
  CORS preflight, or a deployment ([#61](https://github.com/NaimElijah/FinAssist/issues/61)) puts
  a second application on the SPA's host name: the script-readable cookie then protects less than
  this record assumes.
- **A hard `maxActiveSessions` cap on the gateway.** Rejected: it turns a flood of started
  sign-ins into "nobody can sign in", the owner's included. **Revisit when** the gateway is
  reachable from anything but the owner's machine (#61), where a client that sends no Fetch
  Metadata is no longer a local process.
- **Back-channel logout.** Not adopted: Spring Security ships it, and the realm registers no
  back-channel URL. Using it needs a permitted path, a CSRF exemption and the `gateway` Host on
  that one path, for a lag of about four minutes on a system with one owner. **Revisit when** a
  viewer is added who is not the owner's own, or a deployment (#61) exists.

## Revisit when

- **Any listener on the SPA's origin answers a credentialed CORS preflight, or a second
  application shares the SPA's host name.** The CSRF cookie then needs the alternative above.
- **The gateway is reachable from beyond the owner's machine (#61).** The session cap, the
  absent-header rule and back-channel logout are each reopened by it.
- **A first sign-in regularly outlasts 15 minutes.** `PRE_SIGN_IN_IDLE_LIMIT` rises.

## What lands with this record

No code beyond #340's own fixes. Every rule above exists and has a test:

- **The CSRF header.**
  `GatewaySecurityTest.Given_a_signed_in_session_When_logout_carries_the_csrf_token_only_as_a_request_parameter_Then_it_is_refused_403_and_the_session_survives`
  and `GatewayLoginFlowIT.Given_a_signed_in_session_When_a_relayed_write_carries_the_csrf_token_only_as_a_request_parameter_Then_it_is_refused_403_and_not_relayed`.
- **The login entry and its sessions.** `GatewayApplicationIT`:
  `Given_a_browser_request_that_is_not_a_top_level_navigation_When_it_reaches_the_login_entry_Then_it_is_refused_403_and_no_session_is_created`,
  which covers a fetch mode other than `navigate` and a destination other than `document`, and
  `Given_a_sign_in_started_and_never_finished_When_its_session_is_read_Then_its_idle_limit_is_the_pre_sign_in_limit`;
  `GatewayLoginFlowIT`:
  `Given_a_completed_sign_in_When_its_session_is_read_Then_its_idle_limit_is_the_configured_thirty_minutes`.
- **The relayed answer.** `GatewayLoginFlowIT`:
  `Given_an_upstream_answer_carrying_origin_wide_headers_When_relayed_Then_the_browser_receives_none_of_them`.
- **The sign-in failure log.** `SignInFailureHandlerTest`:
  `Given_a_callback_error_that_is_not_a_registered_oauth2_code_When_the_sign_in_fails_Then_the_log_line_carries_none_of_the_callers_text`.
- **The firewall.** `GatewayApplicationIT`:
  `Given_a_path_the_firewall_rejects_When_answered_Then_it_is_a_400_problem_with_a_code`.
- **The web client's servers.** `frontend/web/tests/devServer.test.ts`:
  `Given_a_request_without_sec_fetch_site_When_open_in_editor_is_called_Then_the_dev_server_refuses_it_403`,
  `Given_the_vite_config_When_read_Then_server_and_preview_both_refuse_framing` and
  `Given_the_vite_config_When_read_Then_cors_is_off_and_fs_is_left_strict`; and `UserMenu.test.tsx`:
  `Given_sign_out_is_clicked_When_the_gateway_answers_403_csrf_token_invalid_Then_an_alert_says_the_session_is_still_open`,
  `Given_a_sign_out_whose_pending_button_dropped_focus_When_it_fails_Then_focus_returns_to_sign_out`
  and
  `Given_a_sign_out_that_failed_When_it_fails_again_Then_the_alert_is_mounted_afresh_so_it_is_announced_again`.
- **The raw-HTML rule.** `frontend/web/tests/lintRules.test.ts` lints a snippet of each form through
  ESLint's API.

Documentation:

- **Status lines and README.** ADR-030 reads `Accepted, extended by ADR-037, ADR-053, ADR-057 and
  ADR-058, corrected by ADR-047`, and ADR-029 `Accepted, extended by ADR-030, ADR-047, ADR-056 and
  ADR-057`, each with its row in `docs/ADRs/README.md`, a row for 057 and a note.
- **`backend/gateway/CLAUDE.md`.** The gateway's rules above.
- **`frontend/web/CLAUDE.md`.** The raw-HTML rule.
- **`docs/architecture/architecture.md`.** The rules above as built.
