package io.floci.gcp.services.iam;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The IAM principal identifiers of a Kubernetes ServiceAccount federated through Workload Identity Federation for GKE
 * (IAM "Principal identifiers", allow policies): the {@code principal://.../subject/ns/NS/sa/KSA} member it
 * authenticates as, and the four other forms an allow binding may name it by.
 */
public final class WorkloadIdentityPrincipals {

    private static final String IAM = "principal://iam.googleapis.com/";
    private static final String IAM_SET = "principalSet://iam.googleapis.com/";

    /** One workload: the pool's project number and name, its namespace, ServiceAccount and its UID, and its cluster's issuer URL. */
    public record Workload(String projectNumber, String pool, String namespace, String serviceAccount,
                           String serviceAccountUid, String cluster) {

        public static Workload fromAttributes(Map<String, String> attributes) {
            return new Workload(attributes.get("project_number"), attributes.get("pool"), attributes.get("namespace"),
                    attributes.get("service_account"), attributes.get("service_account_uid"), attributes.get("cluster"));
        }

        public Map<String, String> attributes() {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("project_number", projectNumber);
            attributes.put("pool", pool);
            attributes.put("namespace", namespace);
            attributes.put("service_account", serviceAccount);
            if (serviceAccountUid != null) {
                attributes.put("service_account_uid", serviceAccountUid);
            }
            if (cluster != null) {
                attributes.put("cluster", cluster);
            }
            return attributes;
        }
    }

    private WorkloadIdentityPrincipals() {
    }

    public static IamPrincipal principal(Workload workload) {
        return new IamPrincipal(member(workload), aliases(workload));
    }

    /** {@code principal://iam.googleapis.com/projects/NUMBER/locations/global/workloadIdentityPools/POOL/subject/ns/NS/sa/KSA}. */
    public static String member(Workload workload) {
        return IAM + poolPath(workload) + "/subject/ns/" + workload.namespace() + "/sa/" + workload.serviceAccount();
    }

    /** The UID, legacy, namespace and cluster forms of the same workload. */
    public static Set<String> aliases(Workload workload) {
        Set<String> aliases = new LinkedHashSet<>();
        if (workload.serviceAccountUid() != null) {
            aliases.add(IAM + poolPath(workload) + "/kubernetes.serviceaccount.uid/" + workload.serviceAccountUid());
        }
        aliases.add("serviceAccount:" + workload.pool() + "[" + workload.namespace() + "/" + workload.serviceAccount() + "]");
        aliases.add(IAM_SET + poolPath(workload) + "/namespace/" + workload.namespace());
        if (workload.cluster() != null) {
            aliases.add(IAM_SET + poolPath(workload) + "/kubernetes.cluster/" + workload.cluster());
        }
        return aliases;
    }

    private static String poolPath(Workload workload) {
        return "projects/" + workload.projectNumber() + "/locations/global/workloadIdentityPools/" + workload.pool();
    }
}
