package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.services.iam.IamConditionEvaluator;
import io.floci.gcp.services.iam.IamPrincipal;
import io.floci.gcp.services.iam.IamPrincipalResolver;
import io.floci.gcp.services.iam.IamResourceHierarchy;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.authorization.IamIdentityKind;
import io.floci.gcp.services.iam.model.StoredPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GkeTokenReviewerTest {

    private static final String SA = "deployer@p1.iam.gserviceaccount.com";

    private EmulatorConfig config;
    private IamService policies;
    private GkeTokenReviewer reviewer;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        IamPrincipalResolver principals = mock(IamPrincipalResolver.class);
        when(principals.resolve(anyString())).thenReturn(
                new IamPrincipalResolver.Resolution(IamPrincipal.anonymous(), IamIdentityKind.EXTERNAL));
        when(principals.resolve("Bearer floci-sa")).thenReturn(
                new IamPrincipalResolver.Resolution(IamPrincipal.serviceAccount(SA), IamIdentityKind.FLOCI_SERVICE_ACCOUNT));
        policies = mock(IamService.class);
        when(policies.policyForEvaluation(anyString())).thenReturn(new StoredPolicy());
        reviewer = new GkeTokenReviewer(config, principals, policies, new IamResourceHierarchy(),
                mock(IamConditionEvaluator.class));
    }

    private void mode(EmulatorConfig.IamAuthorizationMode mode) {
        when(config.services().iam().authorizationMode()).thenReturn(mode);
    }

    private void grant(String role, String member) {
        StoredPolicy policy = new StoredPolicy();
        policy.setBindings(List.of(Map.of("role", role, "members", List.of(member))));
        when(policies.policyForEvaluation("projects/p1")).thenReturn(policy);
    }

    @Test
    void withAuthorizationDisabledEveryTokenIsAClusterAdminNamedAfterItsPrincipal() {
        mode(EmulatorConfig.IamAuthorizationMode.DISABLED);

        assertEquals(Optional.of(new GkeTokenReviewer.User(SA, SA, List.of("system:masters"))),
                reviewer.review("floci-sa", "p1", "us-central1", "c1"));
        assertEquals(Optional.of(new GkeTokenReviewer.User("floci:gcp-iam", "floci-gcp-iam", List.of("system:masters"))),
                reviewer.review("ya29.gcloud-user-token", "p1", "us-central1", "c1"));
        assertEquals(Optional.empty(), reviewer.review(" ", "p1", "us-central1", "c1"));
    }

    @Test
    void enforcedOnlyFlociIssuedServiceAccountTokensAuthenticate() {
        mode(EmulatorConfig.IamAuthorizationMode.ENFORCE);

        assertEquals(Optional.empty(), reviewer.review("ya29.gcloud-user-token", "p1", "us-central1", "c1"));
        assertEquals(Optional.of(new GkeTokenReviewer.User(SA, SA, List.of("system:authenticated"))),
                reviewer.review("floci-sa", "p1", "us-central1", "c1"));
    }

    @Test
    void enforcedAdminRolesOnTheProjectMakeAClusterAdmin() {
        mode(EmulatorConfig.IamAuthorizationMode.ENFORCE);
        for (String role : List.of("roles/container.admin", "roles/owner", "roles/editor")) {
            grant(role, "serviceAccount:" + SA);
            assertEquals(List.of("system:authenticated", "system:masters"),
                    reviewer.review("floci-sa", "p1", "us-central1", "c1").orElseThrow().groups(), role);
        }

        grant("roles/container.developer", "serviceAccount:" + SA);
        assertEquals(List.of("system:authenticated"),
                reviewer.review("floci-sa", "p1", "us-central1", "c1").orElseThrow().groups());
        grant("roles/container.admin", "serviceAccount:someone-else@p1.iam.gserviceaccount.com");
        assertEquals(List.of("system:authenticated"),
                reviewer.review("floci-sa", "p1", "us-central1", "c1").orElseThrow().groups());
        grant("roles/container.admin", "serviceAccount:" + SA);
        assertEquals(List.of("system:authenticated"),
                reviewer.review("floci-sa", null, null, null).orElseThrow().groups(),
                "a cluster whose webhook names no project gets no admin from project roles");
    }
}
