package io.floci.gcp.services.gke;

/**
 * The StorageClasses a GKE Standard cluster comes with, backed by a {@code local-path-provisioner}
 * registered under the Compute Engine persistent disk CSI driver's name, so claims bind to node-local
 * volumes. k3s applies the manifest from its auto-deploy directory when the control plane starts.
 */
final class GkeStorage {

    static final String MANIFEST_ENTRY = "server/manifests/floci-gke-storage.yaml";
    static final String PROVISIONER = "pd.csi.storage.gke.io";
    static final String PROVISIONER_IMAGE = "rancher/local-path-provisioner:v0.0.37";
    static final String HELPER_IMAGE = "rancher/mirrored-library-busybox:1.37.0";

    private GkeStorage() {
    }

    static String manifest() {
        return """
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: floci-pd-provisioner
                  namespace: kube-system
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRole
                metadata:
                  name: floci-pd-provisioner
                rules:
                - apiGroups: [""]
                  resources: ["nodes", "persistentvolumeclaims", "configmaps", "pods/log"]
                  verbs: ["get", "list", "watch"]
                - apiGroups: [""]
                  resources: ["endpoints", "persistentvolumes", "pods"]
                  verbs: ["*"]
                - apiGroups: [""]
                  resources: ["events"]
                  verbs: ["create", "patch"]
                - apiGroups: ["storage.k8s.io"]
                  resources: ["storageclasses"]
                  verbs: ["get", "list", "watch"]
                - apiGroups: ["coordination.k8s.io"]
                  resources: ["leases"]
                  verbs: ["get", "list", "watch", "create", "update", "patch"]
                ---
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRoleBinding
                metadata:
                  name: floci-pd-provisioner
                roleRef:
                  apiGroup: rbac.authorization.k8s.io
                  kind: ClusterRole
                  name: floci-pd-provisioner
                subjects:
                - kind: ServiceAccount
                  name: floci-pd-provisioner
                  namespace: kube-system
                ---
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: floci-pd-provisioner
                  namespace: kube-system
                data:
                  config.json: |-
                    {"nodePathMap":[{"node":"DEFAULT_PATH_FOR_NON_LISTED_NODES","paths":["/var/lib/rancher/k3s/storage"]}]}
                  setup: |-
                    #!/bin/sh
                    set -eu
                    mkdir -m 0777 -p "${VOL_DIR}"
                  teardown: |-
                    #!/bin/sh
                    set -eu
                    rm -rf "${VOL_DIR}"
                  helperPod.yaml: |-
                    apiVersion: v1
                    kind: Pod
                    metadata:
                      name: helper-pod
                    spec:
                      containers:
                      - name: helper-pod
                        image: %3$s
                        imagePullPolicy: IfNotPresent
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: floci-pd-provisioner
                  namespace: kube-system
                spec:
                  replicas: 1
                  revisionHistoryLimit: 0
                  selector:
                    matchLabels:
                      app: floci-pd-provisioner
                  template:
                    metadata:
                      labels:
                        app: floci-pd-provisioner
                    spec:
                      priorityClassName: system-cluster-critical
                      serviceAccountName: floci-pd-provisioner
                      tolerations:
                      - operator: Exists
                      nodeSelector:
                        kubernetes.io/os: linux
                      containers:
                      - name: provisioner
                        image: %2$s
                        imagePullPolicy: IfNotPresent
                        command:
                        - local-path-provisioner
                        - start
                        - --config=/etc/config/config.json
                        - --provisioner-name=%1$s
                        - --configmap-name=floci-pd-provisioner
                        - --service-account-name=floci-pd-provisioner
                        - --helper-image=%3$s
                        env:
                        - name: POD_NAMESPACE
                          valueFrom:
                            fieldRef:
                              fieldPath: metadata.namespace
                        - name: CONFIG_MOUNT_PATH
                          value: /etc/config/
                        volumeMounts:
                        - name: config
                          mountPath: /etc/config/
                      volumes:
                      - name: config
                        configMap:
                          name: floci-pd-provisioner
                ---
                apiVersion: storage.k8s.io/v1
                kind: StorageClass
                metadata:
                  name: standard-rwo
                  annotations:
                    storageclass.kubernetes.io/is-default-class: "true"
                provisioner: %1$s
                parameters:
                  type: pd-balanced
                reclaimPolicy: Delete
                volumeBindingMode: WaitForFirstConsumer
                allowVolumeExpansion: true
                ---
                apiVersion: storage.k8s.io/v1
                kind: StorageClass
                metadata:
                  name: premium-rwo
                provisioner: %1$s
                parameters:
                  type: pd-ssd
                reclaimPolicy: Delete
                volumeBindingMode: WaitForFirstConsumer
                allowVolumeExpansion: true
                ---
                apiVersion: storage.k8s.io/v1
                kind: StorageClass
                metadata:
                  name: standard
                provisioner: %1$s
                parameters:
                  type: pd-standard
                reclaimPolicy: Delete
                volumeBindingMode: WaitForFirstConsumer
                allowVolumeExpansion: true
                """.formatted(PROVISIONER, PROVISIONER_IMAGE, HELPER_IMAGE);
    }
}
