# The identity provider, built as code (ADR-031). Both stages start from the same tag, pinned
# exactly and confirmed to exist on quay.io before this file was committed
# (`docker manifest inspect quay.io/keycloak/keycloak:26.8.1`); Keycloak's own container guide
# builds and runs an "optimized" image from one image, unlike a JDK-build/JRE-run split, because
# `kc.sh build` and `kc.sh start --optimized` are the same distribution at two points in its own
# lifecycle.
FROM quay.io/keycloak/keycloak:26.8.1 AS builder

# Baked in at build time, not passed at start-up: `kc.sh build` freezes the database vendor and
# the enabled features into the optimized artifact the runtime stage copies out, and Keycloak
# refuses to change either without rebuilding.
ENV KC_HEALTH_ENABLED=true
ENV KC_DB=postgres
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
# The whole optimized distribution, not a jar: `kc.sh build`'s output is a reconfigured
# Quarkus tree under /opt/keycloak, and a narrower copy would leave `start --optimized` unable
# to find what it built (confirmed against the running image while drafting this file).
COPY --from=builder /opt/keycloak/ /opt/keycloak/

# The realm the container imports at start-up (ADR-031). Every secret and credential in it is a
# `${VAR}` placeholder with no default - KeycloakRealmTest pins that - so no usable value is ever
# committed. Keycloak does not refuse a missing variable, though: it imports the literal
# placeholder text. What stops that is `:?` on each secret's variable in docker-compose.yml, which
# refuses to start the container at all; the realm's two non-secret placeholders take `:-`
# defaults there instead.
#
# The owner user's `id` inside this file is pinned to a fixed UUID
# (6c4a4be0-7c38-4145-845f-f4311ef64202), generated once and committed rather than left for
# Keycloak to assign. A user's `sub` claim is its `id`; leaving it unpinned would hand the owner
# a new `sub` every time the realm is imported afresh - into a recreated `keycloak-db-data`, or by
# `kc.sh import --override true`, which deletes the realm first (see
# docs/operations/operations.md) - and journal rows attributed to the old `sub` would then name
# nobody Keycloak still knows (ADR-031's "makes hard" list; ADR-020, ADR-024, ADR-033).
COPY finassist-realm.json /opt/keycloak/data/import/finassist-realm.json

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
