#!/bin/sh
# Applies ops/keycloak/master-realm.json to the running Keycloak's master realm (ADR-074).
#
# Keycloak creates master itself on its first start, and --import-realm skips a realm that already
# exists, so master's settings cannot come from an import the way finassist's do. This merges the
# file into the realm as it stands, through the admin REST API, and changes nothing the file does
# not name: master's other settings and its users are kept, so the same run applies the settings to
# a fresh volume and to one that has held the realm for years. The keycloak-master one-shot in
# docker-compose.yml runs it on every `up`, and KeycloakRealmIT runs it against the image it builds.
#
# --no-config signs in for this one call and writes no session to disk; the password comes from
# KC_CLI_PASSWORD, never an argument, since kcadm.sh hands its arguments to a java command line
# that any process can read.
set -eu

: "${KEYCLOAK_ADMIN_USERNAME:?set KEYCLOAK_ADMIN_USERNAME}"
: "${KC_CLI_PASSWORD:?set KC_CLI_PASSWORD}"

exec /opt/keycloak/bin/kcadm.sh update realms/master \
    --file /opt/finassist/master-realm.json --merge \
    --no-config --server "${KEYCLOAK_URL:-http://keycloak:8080}" \
    --realm master --user "$KEYCLOAK_ADMIN_USERNAME"
