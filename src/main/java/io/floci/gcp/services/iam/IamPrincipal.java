package io.floci.gcp.services.iam;

import java.util.Set;

/**
 * Canonical principal identity used for IAM allow-binding membership checks: its member string, plus the other member
 * strings IAM treats as the same identity (a GKE workload is named five ways).
 */
public record IamPrincipal(String member, Set<String> aliases) {

    private static final IamPrincipal ANONYMOUS = new IamPrincipal(null);

    public IamPrincipal(String member) {
        this(member, Set.of());
    }

    public static IamPrincipal anonymous() {
        return ANONYMOUS;
    }

    public static IamPrincipal serviceAccount(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("service account email must not be blank");
        }
        return new IamPrincipal("serviceAccount:" + email);
    }

    public boolean isAuthenticated() {
        return member != null;
    }

    public IamPrincipal {
        if (member != null && member.isBlank()) {
            throw new IllegalArgumentException("principal member must not be blank");
        }
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    }
}
