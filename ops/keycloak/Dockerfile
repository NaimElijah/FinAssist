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
# Both are deprecated and on by default in 26.8, and FinAssist brokers no identity provider.
# KeycloakRealmIT reads every deprecated feature back from the built image as disabled.
ENV KC_FEATURES_DISABLED=twitter-broker,identity-brokering-api
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.1
# The whole optimized distribution, not a jar: `kc.sh build`'s output is a reconfigured
# Quarkus tree under /opt/keycloak, and a narrower copy would leave `start --optimized` unable
# to find what it built (confirmed against the running image while drafting this file).
#
# Owned by root, not by the user Keycloak runs as. Upstream leaves the tree owned by uid 1000
# with group-write for gid 0, so the server could rewrite its own `kc.sh`, jars and
# `conf/keycloak.conf`; Keycloak's root filesystem is not read-only (docker-compose.yml), and
# under `restart: unless-stopped` such a change would run on every start. Root ownership keeps
# the identity provider's code and configuration read-only to it, as the Java images' jar is.
COPY --from=builder --chown=0:0 /opt/keycloak/ /opt/keycloak/

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
#
# Copied as root, like the tree around it: the runtime user reads it and cannot change it.
COPY finassist-realm.json /opt/keycloak/data/import/finassist-realm.json

# Two ownership changes COPY cannot make, done once as root and touching no file's content, so
# the layer is metadata alone. COPY re-owns what it copies but never a destination directory
# that already exists, so /opt/keycloak itself would stay uid 1000's, free to rename `bin` or
# `lib` aside and put its own in their place. And the server writes one path under its tree, the
# transaction manager's object store: `docker diff` of a container that started with
# `start --optimized --import-realm`, issued tokens, served the login page and admin console and
# stopped showed `data/transaction-logs` and nothing else under /opt/keycloak, the rest going to
# the world-writable /tmp (JVM perf data, the Vert.x cache, uploads). That one directory is the
# runtime user's alone.
USER root
RUN chown 0:0 /opt/keycloak \
    && install -d -o 1000 -g 1000 -m 0700 /opt/keycloak/data/transaction-logs

# Upstream's own non-root user, uid 1000 in 26.8.1, stated here so an upstream change cannot
# silently move the user this image runs as. The group is stated too: upstream's passwd entry
# gives uid 1000 the primary group 0, which the tree's group-write bits would still admit.
USER 1000:1000

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
