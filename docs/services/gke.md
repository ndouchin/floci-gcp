# GKE (Kubernetes Engine)

floci-gcp emulates the Google Kubernetes Engine control plane (`container.googleapis.com`,
ClusterManager v1) over REST JSON. Clusters are backed by real [k3s](https://k3s.io/)
containers (`rancher/k3s`) started through the host Docker daemon: an agentless control plane
on the k3s release of the cluster's Kubernetes version, and one k3s agent per node pool node.
A lightweight mock mode keeps everything metadata-only.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_GKE_ENABLED` | `true` | Enable/disable GKE |
| `FLOCI_GCP_SERVICES_GKE_MOCK` | `false` | Mock mode: clusters return `RUNNING` immediately without starting a k3s container |
| `FLOCI_GCP_SERVICES_GKE_IMAGE_REPOSITORY` | `rancher/k3s` | Repository of the k3s images pinned per Kubernetes minor version (see [Versions](#versions)) |
| `FLOCI_GCP_SERVICES_GKE_DEFAULT_IMAGE` | _(none)_ | Forces one image on every cluster, whatever its version |
| `FLOCI_GCP_SERVICES_GKE_API_SERVER_BASE_PORT` | `6550` | Start of the host port range for k3s API servers |
| `FLOCI_GCP_SERVICES_GKE_API_SERVER_MAX_PORT` | `6599` | End of the host port range |
| `FLOCI_GCP_SERVICES_GKE_ENDPOINT_MODE` | `host` | How the cluster endpoint is advertised to `kubectl` (`host` for a reachable `host:port`) |
| `FLOCI_GCP_SERVICES_GKE_KEEP_RUNNING_ON_SHUTDOWN` | `false` | Leave spawned k3s containers running after floci-gcp shuts down |
| `FLOCI_GCP_SERVICES_GKE_MAX_NODES_PER_CLUSTER` | `5` | Node containers one cluster runs at most (see [Nodes](#nodes-real-mode)) |
| `FLOCI_GCP_SERVICES_GKE_DOCKER_NETWORK` | _(none)_ | Overrides `FLOCI_GCP_SERVICES_DOCKER_NETWORK` for GKE/k3s sidecars only |
| `FLOCI_GCP_SERVICES_GKE_METADATA_SERVER_ENABLED` | `true` | Runs the GKE metadata server on real clusters (see [Workload Identity](#workload-identity-federation-for-gke)) |
| `FLOCI_GCP_SERVICES_GKE_METADATA_SERVER_RELAY_IMAGE` | `nginx:1.30.6-alpine` | Image of the per-node metadata relay (nginx's stable line, pinned) |

## Routing: how clients reach GKE

On real GCP, `container.googleapis.com` and other APIs share the canonical
`/v1/projects/.../clusters` path and are told apart only by hostname. floci-gcp serves
everything on one port, where that path also belongs to Managed Kafka, so GKE mounts under a
`/container` prefix and a routing filter maps clients onto it two ways:

- **Host mode (SDKs, Terraform/OpenTofu):** point the client endpoint host at `container.*` (e.g.
  `http://container.localhost:4588`). The first DNS label `container` triggers a rewrite of
  `/v1/...` to `/container/v1/...`. `container.localhost` resolves to loopback out of the box on
  most systems (systemd-resolved's synthetic `*.localhost` wildcard on Linux; no `/etc/hosts` edit
  needed on macOS either).
- **Path mode (gcloud / direct):** call the `/container/v1/...` prefix directly, or set a
  custom endpoint base of `<endpoint>/container/v1/`.

### API versions

GKE is served under both `v1` and `v1beta1`, which declare the same HTTP bindings, so either
mode above works with either version (`/v1beta1/...` on a `container.*` host, or
`/container/v1beta1/...`). The `google-beta` Terraform provider and Pulumi gcp (bridged from it)
always call `v1beta1`; point them at the same host mode endpoint
(`container_custom_endpoint = "http://container.localhost:4588/"`).

- Both versions share the same handlers and the same store: a cluster created on one is visible
  on the other.
- Fields that only `v1beta1` defines (for example `workloadAltsConfig`, `protectConfig`,
  `nodeConfig.hostMaintenancePolicy`) are accepted and ignored, like any field the emulator does
  not model. Set to a non-default value, they are named in a DEBUG log line.
- `selfLink` always uses `/container/v1/`.
- The v1beta1-only `ListLocations` method returns `501 UNIMPLEMENTED`.
- Any other API version (`/v2/...` on a `container.*` host, or `/container/v2/...`) returns a
  JSON `404 NOT_FOUND` naming the path.

!!! warning "Terraform/OpenTofu must use host mode, not path mode"
    The `hashicorp/google` provider's GKE client resolves its base URL through a
    `RemoveBasePathVersion` helper that strips the *last path segment* of any custom endpoint,
    on the assumption it is a stray API version suffix (`.../v1/`). Given a path-prefixed
    endpoint like `<endpoint>/container/`, it incorrectly strips `container` too, so
    `container_custom_endpoint = "<endpoint>/container/"` silently resolves to bare `<endpoint>/`
    and every GKE call 404s or is routed to whatever other service happens to own that raw
    `/v1/projects/.../locations/.../clusters` path (Managed Kafka, in this codebase). Set
    `container_custom_endpoint = "http://container.localhost:4588/"` (host mode) instead. Not yet
    wired into `compatibility-tests/compat-terraform`'s CI suite — that suite's floci-gcp
    container is reached as `floci-gcp:4588` on the shared Docker network, so a `container.*`
    host override needs a network alias (or `/etc/hosts` entry) for `container.floci-gcp` in
    `compatibility.yml`, not just the `*.localhost` wildcard resolution local dev gets for free.
    Verified manually against `./mvnw quarkus:dev` instead; see the PR description for the exact
    commands.

!!! note "SDK transport"
    The Cloud Client libraries default to gRPC, which the REST-only emulator does not serve
    for GKE. Build the client with the HttpJson transport
    (`ClusterManagerSettings.newHttpJsonBuilder()`) and a `container.*` endpoint host.

## Quick Start

=== "REST API"

    ```bash
    # Create a cluster (path mode)
    curl -X POST \
      "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters" \
      -H "Content-Type: application/json" \
      -d '{"cluster":{"name":"my-cluster"}}'

    # Get / list clusters
    curl "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/my-cluster"
    curl "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters"

    # Delete a cluster
    curl -X DELETE \
      "http://localhost:4588/container/v1/projects/floci-local/locations/us-central1/clusters/my-cluster"
    ```

=== "gcloud"

    ```bash
    export CLOUDSDK_API_ENDPOINT_OVERRIDES_CONTAINER="http://localhost:4588/container/"
    # gcloud preflights an API-enablement check; point serviceusage at the emulator and
    # disable the enable-API prompt (floci-gcp ignores auth).
    export CLOUDSDK_API_ENDPOINT_OVERRIDES_SERVICEUSAGE="http://localhost:4588/"
    export CLOUDSDK_CORE_SHOULD_PROMPT_TO_ENABLE_API=false

    gcloud container clusters create my-cluster --region=us-central1 --async
    gcloud container clusters list --region=us-central1
    ```

## Versions

Each supported Kubernetes minor runs on one pinned k3s release, and is advertised as
`1.<minor>.<k3s patch>-gke.1000000` (the build number `1000000` is fixed and means nothing). The
channels and defaults follow the [GKE release schedule](https://docs.cloud.google.com/kubernetes-engine/docs/release-schedule)
as of 2026-11-10.

| Minor | k3s image | Advertised version | Channels | Valid master version |
|---|---|---|---|---|
| 1.32 | `rancher/k3s:v1.32.13-k3s1` | `1.32.13-gke.1000000` | EXTENDED | no (extended support only) |
| 1.33 | `rancher/k3s:v1.33.13-k3s2` | `1.33.13-gke.1000000` | EXTENDED | no (extended support only) |
| 1.34 | `rancher/k3s:v1.34.13-k3s1` | `1.34.13-gke.1000000` | REGULAR, STABLE, EXTENDED | yes |
| 1.35 | `rancher/k3s:v1.35.10-k3s1` | `1.35.10-gke.1000000` | RAPID, REGULAR, STABLE, EXTENDED | yes |
| 1.36 | `rancher/k3s:v1.36.6-k3s1` | `1.36.6-gke.1000000` | RAPID, REGULAR, STABLE, EXTENDED | yes |
| 1.37 | `rancher/k3s:v1.37.2-k3s1` | `1.37.2-gke.1000000` | RAPID, REGULAR, EXTENDED | yes |

- `GetServerConfig` lists exactly these versions: `validMasterVersions` the standard-support minors,
  `validNodeVersions` all of them, and one entry per channel with its `defaultVersion` and
  `validVersions`. `defaultClusterVersion` is the REGULAR channel's default, `1.35.10-gke.1000000`,
  as on GKE, where it matches the REGULAR default. Channel defaults: RAPID 1.36, REGULAR, STABLE and
  EXTENDED 1.35.
- A cluster created without `initialClusterVersion` gets its `releaseChannel`'s default, REGULAR's
  without one. `initialClusterVersion`, `UpdateMaster.masterVersion`, `desiredMasterVersion`,
  `desiredNodeVersion`, `UpdateNodePool.nodeVersion` and a node pool's `version` accept the
  documented spellings: `latest` (the highest version, in the cluster's channel when it has one),
  `-` (the default version; for node fields the cluster's master version), `1.X` and `1.X.Y` (the
  advertised version of that minor, when it matches), and an explicit `1.X.Y-gke.N`.
- An explicit `1.X.Y-gke.N` of a supported minor is stored as sent even when it is not the
  advertised version, and runs on that minor's image. A version of any other minor is rejected
  with `400 INVALID_ARGUMENT` and `Master version "<v>" is unsupported.` (`Node version ...` for
  node fields); an alias that matches nothing with `No valid versions with the prefix "<v>" found.`
  Both messages are the ones GKE users report for retired versions in public issue trackers; they
  were not checked against the live API.
- The control plane moves up one minor at a time and never down a minor; a patch downgrade within
  its minor is allowed. Node pools may not run a newer minor than the control plane, nor one more
  than two minors older. These rules come from
  [Upgrade a cluster](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/upgrading-a-cluster);
  the minor-downgrade rejection uses the message that page shows (`Master cannot be upgraded to
  "<v>": specified version is not newer than the current version.`), the other messages are the
  emulator's own.
- On a real cluster, a master version on another minor (`UpdateMaster`, or `UpdateCluster` with
  `desiredMasterVersion`) recreates the k3s server on that minor's image with the same data volume
  and host port, as an in-place k3s upgrade does. The cluster is `RECONCILING` and the operation
  `RUNNING` until the API server answers again. A patch change only updates the recorded version.
  Only a `RUNNING` cluster can be upgraded (`400 FAILED_PRECONDITION` otherwise).
- The k3s API server reports its own version (`v1.35.10+k3s1`), not the GKE one.
- A cluster persisted by an earlier floci-gcp build on a minor that is no longer supported keeps its
  recorded version and runs on the default release's image.

## kubectl Access

Real (non-mock) clusters run an actual k3s API server, so the native
`gcloud container clusters get-credentials` + `kubectl` flow works end to end:

```bash
gcloud container clusters get-credentials my-cluster --region=us-central1
kubectl get nodes
```

`get-credentials` writes a kubeconfig that authenticates with the GCP access token produced by
the `gke-gcloud-auth-plugin`. k3s forwards any bearer token it does not recognise to floci-gcp's
token-authentication webhook, at an address that names the cluster
(`/_floci-gcp/gke/token-webhook/projects/<project>/locations/<location>/clusters/<cluster>`; clusters
started by an earlier build keep calling the bare `/_floci-gcp/gke/token-webhook`). The API server
caches each answer for 30 seconds. Who the token is depends on
`FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE`:

| Token | `disabled` (default) | `enforce` |
|---|---|---|
| Floci-issued service account token (IAM Credentials `generateAccessToken`) | username = the service account email, groups `system:masters` | username = the service account email, groups `system:authenticated`, plus `system:masters` when the account holds `roles/container.admin`, `roles/owner` or `roles/editor` on the cluster's project or its recorded organization |
| Any other non-empty token (a gcloud user token, a made-up string) | username `floci:gcp-iam`, groups `system:masters` | rejected: the API server answers `401` |
| Empty | rejected | rejected |

The email username is what GKE uses: RoleBinding subjects for Google accounts and IAM service
accounts are `kind: User` with the account's email, and authenticated requests are in
`system:authenticated`
([Role-based access control](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/role-based-access-control)).
So under `enforce` a RoleBinding to `deployer@my-project.iam.gserviceaccount.com` grants that
account what it says, and nothing else does unless the account is a project admin. GKE authorizes a
request when the user has "sufficient permissions according to *either*" RBAC or IAM (same page);
the emulator stands in for the IAM side with `system:masters` for the three admin roles only, so
narrower roles such as `roles/container.developer` or `roles/container.viewer` grant nothing in the
cluster, and the `container.clusters.get` permission GKE requires to authenticate is not checked.
Downscoped tokens count as other tokens. Policy changes reach a cluster once the 30-second cache
entry expires.

The cluster endpoint is advertised as a reachable `host:port`
(`FLOCI_GCP_SERVICES_GKE_ENDPOINT_MODE=host`, the default) so the kubeconfig server URL resolves
correctly. `kubectl` run inside the control plane container uses k3s's own client certificate and is
not affected by any of this.

This flow requires a real cluster: it does not apply in mock mode, where no API server is started.

## Restarts and persistence

With a persistent store (`persistent`, `hybrid` or `wal`), clusters survive an emulator restart.
At startup each persisted cluster that had not failed goes to `RECONCILING` while its control
plane comes back, then to `RUNNING` once its API server answers again:

- a surviving k3s container (left running by `FLOCI_GCP_SERVICES_GKE_KEEP_RUNNING_ON_SHUTDOWN=true`,
  or stopped by a Docker restart) is adopted and started if needed, on the host port it publishes;
- otherwise a new container starts on the surviving data volume, which keeps the cluster's CA and
  everything stored in it, on the same host port when that port is still free;
- otherwise the cluster starts fresh on a new volume. Its CA changes, so kubeconfigs written before
  the restart stop working, and a WARN log line says so.

With the default `keep-running-on-shutdown: false`, shutdown removes the containers and their
volumes, so a restart always takes the last path. In `memory` storage mode nothing is restored.
`DeleteCluster` removes the cluster's containers and volumes whatever
`keep-running-on-shutdown` says.

## Mock Mode

Set `FLOCI_GCP_SERVICES_GKE_MOCK=true` to create clusters in memory that report `RUNNING`
immediately without starting a k3s container. Useful for CI and for tools that provision a
cluster but never connect to its API server.

## Node Pools

Node pools are modeled as real, independent resources (not a static field on the cluster):
`CreateCluster` provisions a `default-pool` node pool from the request's top-level
`initialNodeCount`/`nodeConfig` (or from an explicit `nodePools[]` list), and it can be deleted
and replaced with separately-managed pools: the standard
`remove_default_node_pool = true` + standalone `google_container_node_pool` pattern used by
Terraform, OpenTofu, and Pulumi all work end to end.

`config`, `autoscaling`, `management`, `upgradeSettings`, `placementPolicy` and
`maxPodsConstraint` are stored verbatim from the create/update request and echoed back unchanged on
every read. Autoscaling, auto-repair and surge upgrades are not enforced.

### Nodes (real mode)

On a real cluster every node of every pool is a node of the Kubernetes cluster:

- **Control plane.** The k3s server runs with `--disable-agent --egress-selector-mode=cluster`, so
  it schedules nothing itself and `kubectl get nodes` lists only pool nodes, as on GKE. It reaches
  kubelets (logs, exec, metrics) through the agents' tunnels.
- **One container per node.** Each node is a k3s agent container on the cluster's own Docker
  network (`floci-gcp-[<namespace>-]gke-<project>-<cluster>`), which also holds the control plane
  on a fixed address (the tenth of the network's subnet). The control plane is also attached to the
  emulator's configured network when one is set, so the token webhook and in-Docker clients reach it
  as before. Each node keeps its data in its own volume.
- **Node counts.** A pool's `initialNodeCount` (and `SetNodePoolSize.nodeCount`) is per zone, as
  on GKE: a regional cluster's pool runs that many nodes in each of its zones. Node zones are the
  pool's `locations`, defaulting to the cluster's; a region stands for its `-a`, `-b` and `-c`
  zones. `SetLocations` and `desiredLocations` move every pool to the new locations
  ([About node pools](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/node-pools):
  "three nodes in each of the cluster's compute zones"; "all of the node pools are replicated to
  those zones").
- **Node cap.** One cluster runs at most `FLOCI_GCP_SERVICES_GKE_MAX_NODES_PER_CLUSTER` node
  containers (default 5). They go to pools in creation order, the `default-pool` first, round the
  zones; pools past the cap run fewer nodes (possibly none) while the API still reports the
  requested counts, and a WARN log line names the cap and the pools short of nodes.
- **Status and operations.** A new pool is `PROVISIONING`, a resized, upgraded or moved one
  `RECONCILING`, until all its nodes are Ready, then `RUNNING`; its `CreateNodePool`,
  `SetNodePoolSize` or `UpdateNodePool` (version) operation stays `RUNNING` until then. A pool
  with no node is `RUNNING` at once. `CreateCluster` finishes when the API server answers
  `/readyz`, whatever the state of the nodes. `DeleteNodePool` removes the pool's containers and
  Kubernetes nodes before it returns.
- **Versions.** A pool's nodes run the k3s image of the pool's own `version`. A version on another
  minor replaces the pool's nodes with new ones (new names), as a GKE node upgrade does.

### Node names, provider IDs and instance groups

Names follow what GKE shows in its instance groups and instances (for example
`gke-gke2-default-pool-dabb2ff4-76fs` in an instance group
`.../zones/europe-west4-a/instanceGroupManagers/gke-gke2-default-pool-dabb2ff4-grp`, in GKE API
responses captured in Google's [gcpdiag test data](https://github.com/GoogleCloudPlatform/gcpdiag/tree/main/test-data/gke1/json-dumps)):

- node: `gke-<cluster>-<pool>-<8 hex digits>-<4 characters>`, where the hex digits are one hash
  per pool and zone and the last four characters are random. Cluster and pool names are shortened,
  keeping their first characters, so the name fits 63 characters; GKE's own shortening rule is not
  documented.
- `spec.providerID`: `gce://<project>/<zone>/<node name>`, the GCE cloud provider's form.
- `instanceGroupUrls`: one per zone,
  `https://www.googleapis.com/compute/v1/projects/<project>/zones/<zone>/instanceGroupManagers/gke-<cluster>-<pool>-<hash>-grp`,
  filled in mock and real mode. Compute Engine instance groups are not emulated behind them.

!!! warning "Terraform `node_count` and `instanceGroupUrls`"
    The `hashicorp/google` provider reads a node pool's live `node_count` by fetching each
    `instanceGroupUrls` entry from the Compute API. Point `compute_custom_endpoint` at floci-gcp:
    it answers `404` for instance group managers, which the provider treats as a stale URL and
    skips, so `node_count` reads back as `0`; set `ignore_node_count_changes = true` (or ignore
    `node_count` in `lifecycle`) to keep plans clean. Left on the real Compute endpoint, the
    provider's lookup fails.

### Node labels and taints

Every node carries the labels GKE writes into a node's `kube-labels` (as seen in GKE instance
templates in the same gcpdiag test data, and named in Google's
[cluster autoscaler](https://github.com/GoogleCloudPlatform/cluster-autoscaler/blob/main/pkg/cloudprovider/gke/labels/system_labels.go)),
plus the cloud provider's instance-type and topology labels:

| Label | Value |
|---|---|
| `cloud.google.com/gke-nodepool` | pool name ([About node pools](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/node-pools)) |
| `cloud.google.com/machine-family` | machine series, for example `e2` ([ComputeClasses](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/about-custom-compute-classes)) |
| `cloud.google.com/gke-os-distribution` | `cos`, or `ubuntu` for `UBUNTU_CONTAINERD` |
| `cloud.google.com/gke-container-runtime` | `containerd` |
| `cloud.google.com/gke-boot-disk` | `config.diskType`, default `pd-balanced` |
| `cloud.google.com/gke-max-pods-per-node` | `maxPodsConstraint.maxPodsPerNode`, else the cluster's `defaultMaxPodsConstraint`, else `110` |
| `cloud.google.com/gke-provisioning` | `standard`, `spot` or `preemptible` |
| `cloud.google.com/gke-spot` / `cloud.google.com/gke-preemptible` | `true` on Spot / preemptible pools |
| `cloud.google.com/gke-cpu-scaling-level` | vCPU count |
| `cloud.google.com/gke-logging-variant` | `DEFAULT` |
| `cloud.google.com/gke-stack-type` | `IPV4` |
| `cloud.google.com/private-node` | `false` |
| `node.kubernetes.io/instance-type` | machine type |
| `topology.kubernetes.io/region`, `topology.kubernetes.io/zone` | node region and zone |
| `iam.gke.io/gke-metadata-server-enabled` | `true` on nodes that run the GKE metadata server (see [Workload Identity](#workload-identity-federation-for-gke)); absent otherwise |

`config.labels` are added as node labels, and `config.taints` become node taints
(`NO_SCHEDULE`, `PREFER_NO_SCHEDULE`, `NO_EXECUTE` map to `NoSchedule`, `PreferNoSchedule`,
`NoExecute`). Labels and taints apply when a node registers; changing them on an existing pool
does not relabel its nodes.

### Node size

The machine type (`config.machineType`, default `e2-medium`) sizes each node: the container is
limited to its vCPUs and memory, and the kubelet reserves what GKE reserves, so a node's
allocatable CPU and memory are GKE's for that machine type (`e2-medium`: 940m CPU, about 2.9 GiB)
when the Docker host is at least that big. The reservations follow
[Plan GKE Standard node sizes](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/plan-node-sizes)
for node versions before 1.37.2-gke.1325000 (all versions this emulator advertises): CPU 6% of
the first core, 1% of the second, 0.5% of the next two and 0.25% of the rest (1060 millicores on
shared-core E2 types); memory 255 MiB under 1 GiB, else 25% of the first 4 GiB, 20% of the next 4,
10% of the next 8, 6% of the next 112 and 2% above 128 GiB; plus a 100 MiB eviction threshold.
The node's reported capacity is the Docker host's, as a kubelet in a container sees it. Machine
types are read from their name (`<series>-standard|highmem|highcpu-<vCPUs>`); accelerator-optimized
types use the vCPU and memory figures of
[Accelerator-optimized machines](https://docs.cloud.google.com/compute/docs/accelerator-optimized-machines).
A machine type the emulator cannot read runs unlimited.

### GPUs

A pool gets GPUs from `config.accelerators` (the first entry with an `acceleratorType` and a
`acceleratorCount` above zero), or else from its machine type: A2 (`nvidia-tesla-a100`,
`nvidia-a100-80gb`), A3 (`nvidia-h100-80gb`, `nvidia-h100-mega-80gb`, `nvidia-h200-141gb`), A4
(`nvidia-b200`), G2 (`nvidia-l4`) and the whole-GPU G4 types (`nvidia-rtx-pro-6000`), with the GPU
counts of
[Accelerator-optimized machines](https://docs.cloud.google.com/compute/docs/accelerator-optimized-machines).
The accelerator names are the ones [Run GPUs in GKE Standard node pools](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/gpus)
uses. Each node of such a pool:

- carries `cloud.google.com/gke-accelerator=<accelerator type>`, the node selector GKE documents;
- advertises `nvidia.com/gpu: <count>` in its capacity and allocatable, so pods requesting
  `nvidia.com/gpu` schedule on it and only up to that count. There is no GPU behind it: the emulator
  patches the node status where GKE's device plugin would report the devices, and a pod that tries
  to use CUDA fails;
- is tainted `nvidia.com/gpu=present:NoSchedule` when the pool is added to a cluster that already
  has a pool without GPUs, as GKE does. A cluster whose default pool has GPUs, or whose pools all
  have GPUs, gets no taint, and adding a pool without GPUs later does not taint existing GPU pools
  (same page). Pools listed in `CreateCluster.nodePools` count as added in list order.

The control plane runs the `ExtendedResourceToleration` admission plugin, as GKE does, so a pod
requesting `nvidia.com/gpu` gets the matching toleration. GPU partitioning (MIG, time-sharing),
`gpuDriverInstallationConfig` and fractional G4 types are stored and echoed, not modeled.

## Storage classes (real mode)

A real cluster comes with the StorageClasses GKE Standard clusters have, and claims on them bind.
k3s's own `local-path` class is disabled (`--disable=local-storage`); instead the emulator deploys a
[`local-path-provisioner`](https://github.com/rancher/local-path-provisioner)
(`rancher/local-path-provisioner:v0.0.37`, deployment `kube-system/floci-pd-provisioner`)
registered as `pd.csi.storage.gke.io`, the Compute Engine persistent disk CSI driver's name. Volumes
are directories on the node that runs the pod, kept in the node's data volume.

| StorageClass | Default | Provisioner | `type` | Binding mode | Expansion |
|---|---|---|---|---|---|
| `standard-rwo` | yes | `pd.csi.storage.gke.io` | `pd-balanced` | `WaitForFirstConsumer` | `true` |
| `premium-rwo` | | `pd.csi.storage.gke.io` | `pd-ssd` | `WaitForFirstConsumer` | `true` |
| `standard` | | `pd.csi.storage.gke.io` (GKE: `kubernetes.io/gce-pd`) | `pd-standard` | `WaitForFirstConsumer` (GKE: `Immediate`) | `true` |

Sources: GKE pre-installs `standard-rwo` (balanced persistent disk) and `premium-rwo` (SSD
persistent disk) with the CSI driver
([Using the Compute Engine persistent disk CSI Driver](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/persistent-volumes/gce-pd-csi-driver)),
`standard-rwo` is the default class for `ReadWriteOnce` claims and binds `WaitForFirstConsumer`
([Persistent volumes](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/persistent-volumes));
`standard` is the in-tree class from the Kubernetes GCE addon
([`cluster/addons/storage-class/gce/default.yaml`](https://github.com/kubernetes/kubernetes/blob/master/cluster/addons/storage-class/gce/default.yaml)):
`kubernetes.io/gce-pd`, `pd-standard`, expansion allowed, immediate binding. The reclaim policy is
`Delete`, the default the persistent volumes page states. The expansion flag of `standard-rwo` and
`premium-rwo` is the one GKE's documented example classes use; GKE's own objects were not checked.

Deviations, all on what `local-path-provisioner` can do:

- `standard` names `pd.csi.storage.gke.io` and binds `WaitForFirstConsumer`. Under CSI migration
  Kubernetes hands claims on `kubernetes.io/gce-pd` to the `pd.csi.storage.gke.io` provisioner (the
  claim's `volume.kubernetes.io/storage-provisioner` annotation says so on GKE too), but
  `local-path-provisioner` only provisions for classes naming its own provisioner, and a node-local
  volume needs the node the pod lands on.
- Expansion is allowed but never completes: nothing resizes the volumes.
- `ReadWriteMany` claims fail to provision (GKE 1.37.1+ maps them to `regional-rwx`, which is not emulated).
- The provisioner and its helper (`rancher/mirrored-library-busybox:1.37.0`) are pulled by the nodes
  from Docker Hub.

## Networking

Every cluster, mock or real, gets the Pod and Service ranges GKE would give it, reports them, and a
real cluster runs on them.

- **Mode.** VPC-native unless `ipAllocationPolicy.useRoutes` is `true`: "VPC-native is the default
  network mode for all new clusters ... created through any surface, including ... GKE API"
  ([VPC-native clusters](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/alias-ips)).
  `useRoutes` and `useIpAliases` both `true` is rejected, as the
  [API reference](https://docs.cloud.google.com/kubernetes-engine/docs/reference/rest/v1/projects.locations.clusters)
  says it cannot be.
- **Pod range.** VPC-native: `ipAllocationPolicy.clusterIpv4CidrBlock`, else the deprecated
  `ipAllocationPolicy.clusterIpv4Cidr`, else `clusterIpv4Cidr`; routes-based: `clusterIpv4Cidr`. A
  CIDR is used as given; a bare size (`/17`) or nothing gets a block of that size (default `/14`)
  from `10.0.0.0/8` that overlaps no range of the project's other clusters on the same network nor
  the address the cluster reaches the emulator on. GKE picks that block at random
  ([Create a VPC-native cluster](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/alias-ips));
  the emulator takes the first free one so runs are reproducible. Routes-based Pod ranges must be
  `/9` to `/19` ([routes-based cluster](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/routes-based-cluster)).
- **Service range.** VPC-native: `ipAllocationPolicy.servicesIpv4CidrBlock` (or the deprecated
  `servicesIpv4Cidr`) when it is a CIDR, else the GKE-managed `34.118.224.0/20`, which Standard
  clusters from 1.29 use by default; a bare size of `/20` or smaller takes the start of that managed
  range (same VPC-native page). A bare size larger than `/20` gets a block from `10.0.0.0/8`, which
  is a guess. Routes-based: the last `/20` of the Pod range (`10.96.0.0/16` gives `10.96.240.0/20`,
  the example on the routes-based page).
- **Per-node Pod range.** `/24` on routes-based clusters; on VPC-native ones `31 - ceil(log2(Q))`
  with `Q` the cluster's `defaultMaxPodsConstraint.maxPodsPerNode` (110 by default, giving `/24`;
  64 gives `/25`), the formula of the VPC-native page. k3s sizes every node's range alike, so a
  pool's own `maxPodsConstraint` changes its nodes' maximum Pods but not their range.
- **Response.** `clusterIpv4Cidr` and `servicesIpv4Cidr` carry the ranges. A VPC-native cluster's
  `ipAllocationPolicy` is the request's with `useIpAliases: true`, `clusterIpv4Cidr`,
  `clusterIpv4CidrBlock`, `servicesIpv4Cidr`, `servicesIpv4CidrBlock` filled in and `stackType`
  defaulting to `IPV4`; a routes-based cluster's is echoed as sent, and `nodeIpv4CidrSize` is set,
  which the reference says only route-based clusters have.
- **Runtime.** The k3s server runs with `--cluster-cidr`, `--service-cidr` and the controller
  manager's `node-cidr-mask-size-ipv4`, so nodes get their `spec.podCIDR` from the Pod range, Pods
  their addresses from it, the `kubernetes` Service the first Service address (`34.118.224.1`) and
  `kube-system/kube-dns` (CoreDNS, under the Service name GKE uses) the tenth (`34.118.224.10`),
  k3s's rule; that GKE's kube-dns takes the tenth address too was not checked in Google docs.
- **metrics-server.** k3s's bundled metrics-server is kept, so the resource metrics API that GKE's
  [Horizontal Pod autoscaler](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/horizontalpodautoscaler)
  queries is served and `kubectl top` works. How GKE deploys its own metrics-server was not checked.
- **Not modeled.** Named secondary ranges (`clusterSecondaryRangeName`, `servicesSecondaryRangeName`)
  are not looked up in Compute: give the CIDR too, or the defaults apply. IPv6 and dual-stack,
  additional Pod ranges and network policy enforcement are stored and echoed only. A Pod range
  overlapping the Docker networks of the host breaks node traffic. Clusters stored by an earlier
  floci-gcp build keep running on k3s's own ranges (`10.42.0.0/16`, `10.43.0.0/16`).

## Workload Identity Federation for GKE

Real clusters run Workload Identity Federation for GKE end to end: a Pod asks the metadata server at
`169.254.169.254` (or `metadata.google.internal`) for a token, the metadata server gets a token for the
Pod's Kubernetes ServiceAccount from the cluster's API server, exchanges it at floci-gcp's STS for a federated
token, and, when the ServiceAccount is annotated with `iam.gke.io/gcp-service-account`, exchanges that for
the Google service account's token through IAM Credentials `generateAccessToken`
([About Workload Identity Federation for GKE](https://docs.cloud.google.com/kubernetes-engine/docs/concepts/workload-identity),
[Authenticate to Google Cloud APIs from GKE workloads](https://docs.cloud.google.com/kubernetes-engine/docs/how-to/workload-identity)).
Google client libraries in the Pod then work unchanged against floci-gcp's APIs.

### Cluster and node pool fields

- `workloadIdentityConfig.workloadPool` accepts only the cluster project's pool, `PROJECT_ID.svc.id.goog`
  ("the only accepted workload pool is the workload pool of the Cloud project containing the cluster",
  [`gcloud container clusters create --workload-pool`](https://docs.cloud.google.com/sdk/gcloud/reference/container/clusters/create)).
  Anything else is `400 INVALID_ARGUMENT` (GKE's exact code and message are not verified). It is set at
  create or with `UpdateCluster` `desiredWorkloadIdentityConfig`; an empty pool disables Workload Identity,
  as Terraform sends it, and the key then disappears from `GetCluster`. The v1beta1 `identityNamespace`
  is read as the pool. Autopilot clusters (`autopilot.enabled`) always get the project's pool.
- Clusters carry an `id` (64 hex digits; GKE's format is not verified), which the metadata server reports
  as `cluster-uid`.
- Node pools: `config.workloadMetadataConfig.mode` defaults to `GKE_METADATA` for pools created while the
  cluster has a workload pool ("All new node pools ... default to using Workload Identity Federation for
  GKE"), and is stored so Terraform reads a stable value. Enabling Workload Identity later leaves existing
  pools as they were. `GKE_METADATA` on a cluster without a workload pool is `400 INVALID_ARGUMENT` (code
  not verified). The v1beta1 `nodeMetadata` is read too (`GKE_METADATA_SERVER` means `GKE_METADATA`,
  `EXPOSE` and `SECURE` mean `GCE_METADATA`). `UpdateNodePool` applies a new mode in place: the pool's
  nodes get or lose the `iam.gke.io/gke-metadata-server-enabled=true` label and their metadata routing
  changes, without recreating them (whether GKE recreates nodes is not verified). Disabling Workload
  Identity while pools still say `GKE_METADATA` is accepted and those nodes then serve Compute Engine
  metadata (GKE's behavior is not verified).

### Issuer and keys

New clusters sign ServiceAccount tokens under GKE's issuer,
`https://container.googleapis.com/v1/projects/PROJECT/locations/LOCATION/clusters/CLUSTER`, with
`.../jwks` as JWKS URI and the issuer as default token audience (the audience GKE uses was observed by a
third party, not verified); k3s's own issuer `https://kubernetes.default.svc.cluster.local` stays accepted
([Kubernetes: ServiceAccount issuer discovery](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/)).
k3s keeps its signing key. After the API server is ready (and after every restore or control plane
replacement) floci-gcp reads its `/openid/v1/jwks`; `GetJSONWebKeys` returns those keys in the `Jwk`
fields of `cluster_service.proto`, and `GET .../clusters/CLUSTER/.well-known/openid-configuration`
([getOpenid-configuration](https://docs.cloud.google.com/kubernetes-engine/docs/reference/rest/v1/projects.locations.clusters.well-known/getOpenid-configuration))
returns the issuer, `jwks_uri`, `response_types_supported: ["id_token"]`, `subject_types_supported:
["public"]` and `id_token_signing_alg_values_supported: ["RS256"]`, as the API server's own document does;
`claims_supported`, `grant_types` and the `Cache-Control` header are not served. Mock clusters return an
empty key set.

A cluster stored by an earlier floci-gcp build keeps its server and k3s's issuer when restored. Enabling
Workload Identity on it with `desiredWorkloadIdentityConfig` recreates its control plane on its volume with
the GKE issuer (the `UPDATE_CLUSTER` operation stays `RUNNING` until the API server answers again), which
only a `RUNNING` cluster can do.

### The metadata server on each node

GKE runs `gke-metadata-server` as a DaemonSet that intercepts `169.254.169.254:80` on each node; Workload
Identity Pods reach it at `169.254.169.252:988`, the address a NetworkPolicy must allow. floci-gcp puts a
small relay container (`nginx`, `FLOCI_GCP_SERVICES_GKE_METADATA_SERVER_RELAY_IMAGE`) in each node
container's network namespace, adds both addresses to the node's loopback, and inserts a
`FLOCI-GKE-METADATA` chain first in `nat PREROUTING`: Pod traffic to `169.254.169.254:80` goes to the
`169.254.169.252:988` listener on nodes that run the GKE metadata server, and to the local
`169.254.169.254:80` listener (Compute Engine metadata) on other nodes; node processes and `hostNetwork` Pods
reach the local listener directly. The relay forwards every request to floci-gcp with the caller's
address, which local DNAT preserves and which identifies the Pod (how GKE identifies the Pod is not
documented; a third party reports the same lookup by client address). A `coredns-custom` ConfigMap
(`gke-metadata.server`) makes `metadata.google.internal` resolve to `169.254.169.254` in Pods, and node
containers resolve it for `hostNetwork` Pods. k3s re-applies that ConfigMap whenever the control plane
starts, so edits to it do not last.

Differences from GKE: no `gke-metadata-server` DaemonSet is visible in `kube-system`; the relay is a
container next to the node. A NetworkPolicy behaves as GKE documents for non-Dataplane-V2 clusters: allowing
egress to `169.254.169.252/32` TCP 988 is what lets a Pod reach the metadata server. A relay whose node
container restarted is recreated within about 15 seconds. When floci-gcp itself runs in a Docker container
on a network (configured or detected), node containers also join that network so the relay reaches it,
with `--flannel-iface=eth0` keeping Pod traffic on the cluster network; natively on Linux they get the
`host.docker.internal` host-gateway alias, as the control plane does.

What the metadata server serves ("Metadata server endpoints"):

| Key | Value |
|---|---|
| `instance/hostname` | `NODE.ZONE.c.PROJECT.internal` (floci-gcp's Compute format; GKE's not verified) |
| `instance/id` | a stable 19-digit number derived from the node's provider ID |
| `instance/zone` | `projects/NUMBER/zones/ZONE` |
| `instance/attributes/cluster-location`, `cluster-name`, `cluster-uid` | the cluster's location, name and `id`; other attributes are `404`, as GKE documents |
| `instance/service-accounts/default/` and `.../EMAIL/` | `aliases`, `email`, `identity`, `scopes`, `token` |
| `project/project-id`, `project/numeric-project-id` | the project and its number |
| `universe/universe-domain` | `googleapis.com` (served by floci-gcp's Compute server; GKE not verified) |

`recursive`, `alt` and redirects behave as floci-gcp's [Compute metadata server](compute.md); requests
need `Metadata-Flavor: Google` and must not carry `X-Forwarded-For` (`403`, as floci-gcp's Compute server
does; GKE's handling is not verified), except the root `/`, which answers `computeMetadata/` with
`Metadata-Flavor: Google` for client-library detection. `wait_for_change` waits its whole timeout, since
these values never change while a node runs. With the control plane unreachable, a request that needs it
answers `500`, as GKE documents.

Who the caller is:

| Caller | `email` | `token` |
|---|---|---|
| Pod of a `GKE_METADATA` node, ServiceAccount annotated `iam.gke.io/gcp-service-account: GSA` | `GSA` | `GSA`'s access token (`generateAccessToken` with the federated token) |
| Same, not annotated | `PROJECT_ID.svc.id.goog` (Google's docs say `SERVICEACCOUNT_NAME.svc.id.goog`; what that placeholder means is **not verified**) | the federated token itself |
| ServiceAccount annotated `iam.gke.io/return-principal-id-as-email: "true"` | the `principal://` identifier | as above |
| Node, `hostNetwork` Pod, or Pod of a `GCE_METADATA` node | the pool's `config.serviceAccount`, else `PROJECT_NUMBER-compute@developer.gserviceaccount.com` | a Floci-issued token for that account |

Details: the ServiceAccount token is a TokenRequest bound to the Pod with audience `PROJECT_ID.svc.id.goog`
and the API server's minimum lifetime of 10 minutes (Google's Secret Manager CSI provider asks for 15;
GKE's value is not verified); tokens are cached per Pod until five minutes before they expire (client
libraries refresh "when the token expires in less than 3 minutes 45 seconds"). `scopes` is
`https://www.googleapis.com/auth/cloud-platform` for workloads (not verified) and the pool's
`config.oauthScopes`, else gcloud's default node scopes, for nodes. `identity?audience=` mints the
annotated Google service account's ID token through IAM Credentials `generateIdToken`; for an unannotated
ServiceAccount it answers `404` (not verified); for nodes it mints the same unsigned token as floci-gcp's
Compute server. `iam.gke.io/credential-quota-project` and `iam.gke.io/gcp-service-account-delegates` are
ignored.

### Granting access

Both ways GKE documents work, through floci-gcp's IAM:

- Direct: bind a role to the workload's principal identifier
  `principal://iam.googleapis.com/projects/NUMBER/locations/global/workloadIdentityPools/PROJECT_ID.svc.id.goog/subject/ns/NAMESPACE/sa/KSA`.
  The other forms IAM documents for the same workload also match: `.../kubernetes.serviceaccount.uid/UID`,
  `serviceAccount:PROJECT_ID.svc.id.goog[NAMESPACE/KSA]`,
  `principalSet://.../namespace/NAMESPACE` and `principalSet://.../kubernetes.cluster/ISSUER`
  ([Principal identifiers](https://docs.cloud.google.com/iam/docs/principal-identifiers)).
- Linked: grant `roles/iam.workloadIdentityUser` on the Google service account to one of those members and
  annotate the ServiceAccount ("Both the IAM allow policy and the annotation are required").

As everywhere in floci-gcp, IAM is only evaluated under `FLOCI_GCP_SERVICES_IAM_AUTHORIZATION_MODE=enforce`.
With the default `disabled`, an annotated ServiceAccount gets its Google service account's token without
any binding. Under `enforce`, a missing `roles/iam.workloadIdentityUser` binding, or a Google service
account that does not exist, makes the `token` request answer `403` with the text GKE documents a workload
logging: `generic::permission_denied: loading: GenerateAccessToken("GSA", ""): googleapi: Error 403:
Permission 'iam.serviceAccounts.getAccessToken' denied on resource (or it may not exist).`
([Troubleshoot GKE authentication](https://docs.cloud.google.com/kubernetes-engine/docs/troubleshooting/authentication)).
The status code is modelled on that log line and not verified. Feeding the `PROJECT_ID.svc.id.goog` email
to IAM (for example to sign a URL) fails with `400 Invalid form of account ID ...`, as GCP does.

Not emulated: Workload Identity Federation pools and providers other than GKE's, the pool as an IAM
resource, deny policies and Principal Access Boundaries, STS and metadata server quotas, IAM propagation
delay, and the token webhook accepting federated tokens (they authenticate to the cluster's API server like
any other token not issued for a service account).

## Supported Operations

- `CreateCluster`, `GetCluster`, `ListClusters`, `DeleteCluster`, `UpdateCluster`, `UpdateMaster`
- `SetResourceLabels`, `SetMasterAuth`, `SetNetworkPolicy`, `SetAddonsConfig`,
  `SetLoggingService`, `SetMonitoringService`, `SetLocations`, `SetLegacyAbac`,
  `SetMaintenancePolicy`, `StartIPRotation`, `CompleteIPRotation`
- `CreateNodePool`, `GetNodePool`, `ListNodePools`, `DeleteNodePool`, `UpdateNodePool`
- `SetNodePoolAutoscaling`, `SetNodePoolManagement`, `SetNodePoolSize`,
  `CompleteNodePoolUpgrade`, `RollbackNodePoolUpgrade`
- `GetServerConfig`, `GetJSONWebKeys`, `GetOpenIDConfig` (`.well-known/openid-configuration`),
  `ListUsableSubnetworks`, `CheckAutopilotCompatibility`, `FetchClusterUpgradeInfo`,
  `FetchNodePoolUpgradeInfo`
- `GetOperation` / `ListOperations`

`ListClusters` and `ListOperations` accept `-` as the location
(`projects/{project}/locations/-`) to list across all zones and regions.

This is the full `container.v1` `ClusterManager` RPC surface except
`CancelOperation` (operations are always synchronous/`DONE`, so there is
nothing in flight to cancel).

`GetJSONWebKeys` returns the keys of the cluster's API server (empty in mock mode); see
[Workload Identity](#workload-identity-federation-for-gke). The four other read-only RPCs above report
honest stub data rather than fabricated analysis, since floci-gcp has no real infrastructure behind them
to inspect:

- `ListUsableSubnetworks` returns the single synthetic "default"
  network/subnetwork every cluster in this emulator already defaults to —
  floci-gcp does not emulate Compute Engine/VPC, so there is no real
  subnetwork inventory.
- `CheckAutopilotCompatibility` always reports no issues — there is no real
  policy engine to evaluate a cluster's configuration against.
- `FetchClusterUpgradeInfo`/`FetchNodePoolUpgradeInfo` report the current
  version as both current and target with nothing pending: floci-gcp runs no
  auto-upgrade, so nothing is ever scheduled.
- `UpdateCluster` with `desiredNodeVersion` upgrades the single node pool named
  by `desiredNodePoolId`, matching the real API, where that field is mandatory
  once a cluster has more than one pool. With exactly one pool the id may be
  omitted; with several it is required, and a request without it is rejected
  as `400 INVALID_ARGUMENT` rather than silently upgrading pools the caller
  did not name.
  `desiredMasterVersion` and `desiredNodeVersion` accept the same aliases as
  `UpdateMaster` below, with the documented difference that `-` on the node side
  picks the cluster's current master version rather than the server default. This
  is what `gcloud container clusters upgrade` sends when no `--cluster-version` is
  given, so both forms of that command leave the cluster on a real version.
- `UpdateMaster` moves only the control plane: `currentMasterVersion` changes and
  `currentNodeVersion` and every node pool's `version` stay as they were, as in real
  GKE, where the master and node pools upgrade independently. `masterVersion` is
  required (`400 INVALID_ARGUMENT` when missing). Its aliases and checks are described
  under [Versions](#versions); a value outside the five documented shapes (a bare `1`,
  for example) is rejected with `400 INVALID_ARGUMENT`. The operation is reported as
  `UPGRADE_MASTER`, the `Operation.Type` real GKE uses.

**Autopilot mode** (`autopilot.enabled`) and **Fleet/Anthos registration**
(`fleet`) are not semantically modeled — floci-gcp does not run a real
Autopilot node-management control loop or register with a real Fleet — but
both round-trip correctly through `extraConfig` like every other config
block the emulator doesn't act on, so tooling that merely checks
`cluster.autopilot.enabled` or `cluster.fleet.project` works correctly.

Mutations return a `DONE` Operation, except on real clusters where the runtime has work to finish:
`CreateCluster`, a control plane upgrade onto another minor, `CreateNodePool`, `SetNodePoolSize`
and an `UpdateNodePool` onto another minor return a `RUNNING` operation that finishes when the
control plane or the pool's nodes are ready (see [Versions](#versions) and [Nodes](#nodes-real-mode)).
`CompleteNodePoolUpgrade` returns `google.protobuf.Empty` (`{}`) as the real API does.

`StartIPRotation`/`CompleteIPRotation` acknowledge the request (bumping the cluster's
fingerprint/etag) rather than performing a real dual-certificate rotation window — there is no
live client traffic to migrate off an old certificate in this emulator.
`CompleteNodePoolUpgrade`/`RollbackNodePoolUpgrade` validate the node pool exists, then return
`{}` and an Operation respectively; there is no surge upgrade in flight to complete or roll back.
`GetServerConfig` is described under
[Versions](#versions).

## Limitations

- Most operations resolve synchronously; on real clusters, cluster creation, control plane
  upgrades, node pool creation, resizes and node version changes run until the runtime is ready.
  `CancelOperation` is not implemented.
- Node IAM is not modeled.
- Real Autopilot node management, the cluster autoscaler and Compute Engine instance groups
  (`node_count` read-back, see the warning above) are out of scope; Autopilot mode and Fleet
  registration round-trip as config but are not semantically enforced.
- Cluster-level config blocks the emulator doesn't act on semantically (network policy
  enforcement, binary authorization, private cluster networking, etc.) are stored and echoed back
  verbatim rather than enforced — see the `config`/`autoscaling`/`management` note under Node
  Pools above, which applies at the cluster level too (`extraConfig`).
