package io.floci.gcp.services.iam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamPolicyEvaluatorTest {

    private IamPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new IamPolicyEvaluator(new IamRoleCatalog(), new IamResourceHierarchy(),
                new NessieIamConditionEvaluator(Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC), 8));
    }

    @Test
    void bucketPolicyGrantsObjectViewerPermissionToMatchingServiceAccount() {
        IamResource object = IamResource.gcsObject("reports", "daily.csv");
        IamPolicy policy = policy(binding("roles/storage.objectViewer",
                List.of("serviceAccount:reader@example.test"), null));

        assertTrue(evaluator.isAllowed(IamPrincipal.serviceAccount("reader@example.test"), "storage.objects.get",
                object, Map.of("buckets/reports", policy)));
        assertFalse(evaluator.isAllowed(IamPrincipal.serviceAccount("reader@example.test"), "storage.objects.get",
                IamResource.gcsObject("other", "daily.csv"), Map.of("buckets/reports", policy)));
    }

    @Test
    void supportsPublicAndAuthenticatedMembersButNotUnsupportedMembers() {
        IamResource object = IamResource.gcsObject("reports", "daily.csv");
        IamPolicy publicPolicy = policy(binding("roles/storage.objectViewer", List.of("allUsers"), null));
        IamPolicy authenticatedPolicy = policy(binding("roles/storage.objectViewer",
                List.of("allAuthenticatedUsers"), null));
        IamPolicy groupPolicy = policy(binding("roles/storage.objectViewer", List.of("group:readers@example.test"), null));

        assertTrue(evaluator.isAllowed(IamPrincipal.anonymous(), "storage.objects.get", object,
                Map.of("buckets/reports", publicPolicy)));
        assertFalse(evaluator.isAllowed(IamPrincipal.anonymous(), "storage.objects.get", object,
                Map.of("buckets/reports", authenticatedPolicy)));
        assertTrue(evaluator.isAllowed(IamPrincipal.serviceAccount("reader@example.test"), "storage.objects.get", object,
                Map.of("buckets/reports", authenticatedPolicy)));
        assertFalse(evaluator.isAllowed(IamPrincipal.serviceAccount("reader@example.test"), "storage.objects.get", object,
                Map.of("buckets/reports", groupPolicy)));
    }

    @Test
    void falseConditionalBindingDoesNotOverrideUnconditionalGrant() {
        IamResource object = IamResource.gcsObject("reports", "daily.csv");
        IamPolicy policy = policy(
                binding("roles/storage.objectViewer", List.of("serviceAccount:reader@example.test"),
                        new IamCondition("expired", "request.time < timestamp('2020-01-01T00:00:00Z')", null)),
                binding("roles/storage.objectViewer", List.of("serviceAccount:reader@example.test"), null));

        assertTrue(evaluator.isAllowed(IamPrincipal.serviceAccount("reader@example.test"), "storage.objects.get", object,
                Map.of("buckets/reports", policy)));
    }

    @Test
    void unknownRoleAndFalseConditionNeverGrant() {
        IamResource object = IamResource.gcsObject("reports", "daily.csv");
        IamPrincipal principal = IamPrincipal.serviceAccount("reader@example.test");
        IamPolicy unknownRole = policy(binding("roles/custom.reader", List.of(principal.member()), null));
        IamPolicy falseCondition = policy(binding("roles/storage.objectViewer", List.of(principal.member()),
                new IamCondition("private", "resource.name.startsWith('projects/_/buckets/reports/objects/private/')", null)));

        assertFalse(evaluator.isAllowed(principal, "storage.objects.get", object, Map.of("buckets/reports", unknownRole)));
        assertFalse(evaluator.isAllowed(principal, "storage.objects.get", object, Map.of("buckets/reports", falseCondition)));
    }

    @Test
    void malformedConditionalPublicOrVersionOneBindingNeverGrants() {
        IamResource object = IamResource.gcsObject("reports", "daily.csv");
        IamCondition trueCondition = new IamCondition("true", "true", null);
        IamPolicy conditionalPublic = policy(binding("roles/storage.objectViewer", List.of("allUsers"), trueCondition));
        IamPolicy versionOneCondition = new IamPolicy(1, List.of(binding("roles/storage.objectViewer",
                List.of("serviceAccount:reader@example.test"), trueCondition)), "etag");

        assertFalse(evaluator.isAllowed(IamPrincipal.anonymous(), "storage.objects.get", object,
                Map.of("buckets/reports", conditionalPublic)));
        assertFalse(evaluator.isAllowed(IamPrincipal.serviceAccount("reader@example.test"), "storage.objects.get", object,
                Map.of("buckets/reports", versionOneCondition)));
    }

    @Test
    void aGkeWorkloadMatchesEveryMemberFormOfItsIdentity() {
        String pool = "projects/123456789012/locations/global/workloadIdentityPools/my-proj.svc.id.goog";
        String cluster = "https://container.googleapis.com/v1/projects/my-proj/locations/us-central1/clusters/c1";
        IamPrincipal workload = WorkloadIdentityPrincipals.principal(new WorkloadIdentityPrincipals.Workload(
                "123456789012", "my-proj.svc.id.goog", "team", "app", "6f1c-uid", cluster));
        IamResource object = IamResource.gcsObject("reports", "daily.csv");

        for (String member : List.of(
                "principal://iam.googleapis.com/" + pool + "/subject/ns/team/sa/app",
                "principal://iam.googleapis.com/" + pool + "/kubernetes.serviceaccount.uid/6f1c-uid",
                "serviceAccount:my-proj.svc.id.goog[team/app]",
                "principalSet://iam.googleapis.com/" + pool + "/namespace/team",
                "principalSet://iam.googleapis.com/" + pool + "/kubernetes.cluster/" + cluster)) {
            assertTrue(evaluator.isAllowed(workload, "storage.objects.get", object, Map.of("buckets/reports",
                    policy(binding("roles/storage.objectViewer", List.of(member), null)))), member);
        }
        for (String member : List.of(
                "principal://iam.googleapis.com/" + pool + "/subject/ns/other/sa/app",
                "principal://iam.googleapis.com/" + pool + "/subject/ns/team/sa/other",
                "principal://iam.googleapis.com/projects/999999999999/locations/global/workloadIdentityPools/"
                        + "my-proj.svc.id.goog/subject/ns/team/sa/app",
                "principal://iam.googleapis.com/projects/123456789012/locations/global/workloadIdentityPools/"
                        + "other.svc.id.goog/subject/ns/team/sa/app",
                "serviceAccount:my-proj.svc.id.goog[team/other]",
                "principalSet://iam.googleapis.com/" + pool + "/namespace/other",
                "principalSet://iam.googleapis.com/" + pool + "/kubernetes.cluster/" + cluster + "-2")) {
            assertFalse(evaluator.isAllowed(workload, "storage.objects.get", object, Map.of("buckets/reports",
                    policy(binding("roles/storage.objectViewer", List.of(member), null)))), member);
        }
    }

    private static IamPolicy policy(IamBinding... bindings) {
        return new IamPolicy(3, List.of(bindings), "etag");
    }

    private static IamBinding binding(String role, List<String> members, IamCondition condition) {
        return new IamBinding(role, members, condition);
    }
}
