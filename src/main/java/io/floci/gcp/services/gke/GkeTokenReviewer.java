package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.iam.IamBinding;
import io.floci.gcp.services.iam.IamConditionEvaluator;
import io.floci.gcp.services.iam.IamPolicy;
import io.floci.gcp.services.iam.IamPolicyEvaluator;
import io.floci.gcp.services.iam.IamPolicyNormalizer;
import io.floci.gcp.services.iam.IamPrincipal;
import io.floci.gcp.services.iam.IamPrincipalResolver;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.IamResourceHierarchy;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.authorization.IamIdentityKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides who a bearer token sent to a cluster's API server is. With IAM authorization disabled (the
 * default) every non-empty token is a cluster admin, named after the service account a Floci-issued
 * token belongs to. With {@code iam.authorization-mode=enforce} only Floci-issued service account tokens
 * authenticate, as their email, and only principals holding an admin role on the cluster's project are
 * cluster admins; the cluster's RBAC decides for everyone else.
 */
@ApplicationScoped
public class GkeTokenReviewer {

    static final String UNRESOLVED_USERNAME = "floci:gcp-iam";
    static final String UNRESOLVED_UID = "floci-gcp-iam";
    static final String MASTERS = "system:masters";
    static final String AUTHENTICATED = "system:authenticated";
    static final Set<String> ADMIN_ROLES = Set.of("roles/container.admin", "roles/owner", "roles/editor");

    /** The Kubernetes user a token authenticates as. */
    public record User(String username, String uid, List<String> groups) {
    }

    private final EmulatorConfig config;
    private final IamPrincipalResolver principals;
    private final IamService policies;
    private final IamResourceHierarchy hierarchy;
    private final IamConditionEvaluator conditions;

    @Inject
    public GkeTokenReviewer(EmulatorConfig config, IamPrincipalResolver principals, IamService policies,
                            IamResourceHierarchy hierarchy, IamConditionEvaluator conditions) {
        this.config = config;
        this.principals = principals;
        this.policies = policies;
        this.hierarchy = hierarchy;
        this.conditions = conditions;
    }

    /**
     * The user a token authenticates as on a cluster of {@code project} (null when the cluster's webhook
     * predates per-cluster webhook addresses), or empty when it does not authenticate.
     */
    public Optional<User> review(String token, String project, String location, String clusterName) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        IamPrincipalResolver.Resolution resolution = principals.resolve("Bearer " + token.trim());
        Optional<String> email = resolution.kind() == IamIdentityKind.FLOCI_SERVICE_ACCOUNT
                ? Optional.of(resolution.principal().member().substring("serviceAccount:".length()))
                : Optional.empty();
        if (!enforced()) {
            return Optional.of(new User(email.orElse(UNRESOLVED_USERNAME), email.orElse(UNRESOLVED_UID), List.of(MASTERS)));
        }
        if (email.isEmpty()) {
            return Optional.empty();
        }
        boolean admin = project != null && holdsAdminRole(resolution.principal(), project, location, clusterName);
        return Optional.of(new User(email.get(), email.get(),
                admin ? List.of(AUTHENTICATED, MASTERS) : List.of(AUTHENTICATED)));
    }

    private boolean enforced() {
        return config.services().iam().authorizationMode() == EmulatorConfig.IamAuthorizationMode.ENFORCE;
    }

    /** Whether an allow binding on the project, or its recorded organization, gives the principal an admin role. */
    private boolean holdsAdminRole(IamPrincipal principal, String project, String location, String clusterName) {
        String projectResource = "projects/" + project;
        IamResource cluster = new IamResource("container.googleapis.com", "container.googleapis.com/Cluster",
                projectResource + "/locations/" + location + "/clusters/" + clusterName, projectResource,
                projectResource, true);
        for (String key : hierarchy.policyResourcesFor(cluster)) {
            IamPolicy policy = IamPolicyNormalizer.normalize(policies.policyForEvaluation(key));
            for (IamBinding binding : policy.bindings()) {
                if (ADMIN_ROLES.contains(binding.role()) && IamPolicyEvaluator.matchesPrincipal(principal, binding)
                        && (binding.condition() == null || conditions.matches(binding.condition(), cluster))) {
                    return true;
                }
            }
        }
        return false;
    }
}
