# GKE (Kubernetes Engine)

floci-gcp emulates the Google Kubernetes Engine control plane (`container.googleapis.com`,
ClusterManager v1) over REST JSON. Cluster lifecycle is backed by real
[k3s](https://k3s.io/) containers (`rancher/k3s`) started through the host Docker daemon,
or a lightweight mock mode.

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
| `FLOCI_GCP_SERVICES_GKE_DOCKER_NETWORK` | _(none)_ | Overrides `FLOCI_GCP_SERVICES_DOCKER_NETWORK` for GKE/k3s sidecars only |

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
token-authentication webhook. Because floci-gcp does not validate credentials, the webhook accepts any
non-empty token and maps it to `cluster-admin`. The cluster endpoint is advertised as a reachable
`host:port` (`FLOCI_GCP_SERVICES_GKE_ENDPOINT_MODE=host`, the default) so the kubeconfig server URL
resolves correctly.

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
and replaced with separately-managed pools — the standard
`remove_default_node_pool = true` + standalone `google_container_node_pool` pattern used by
Terraform, OpenTofu, and Pulumi all work end to end.

`config`, `autoscaling`, `management`, `upgradeSettings` and `placementPolicy` are stored
verbatim from the create/update request and echoed back unchanged on every read — floci-gcp does
not run real node VMs or enforce autoscaling/repair/upgrade behavior, so there is nothing to act
on semantically. This keeps every field a real client sends round-tripping consistently for
Terraform's plan/refresh diff, without hand-modeling the full `NodeConfig` proto surface.

!!! warning "`node_count` drifts on every `terraform plan` unless you set `ignore_node_count_changes`"
    Real GKE's Terraform provider computes the live `node_count` value by querying **Compute
    Engine Instance Group Manager** target sizes for the node pool's `instanceGroupUrls` — not
    from any field the NodePool API itself returns. floci-gcp does not emulate Compute Engine, so
    `instanceGroupUrls` is always empty and the provider reads `node_count` back as `0`, which it
    then wants to "correct" on every plan. Set `ignore_node_count_changes = true` on
    `google_container_node_pool` resources to skip that IGM-based read entirely and use
    `initial_node_count` as the source of truth instead (the provider's own documented escape
    hatch for exactly this situation).

## Supported Operations

- `CreateCluster`, `GetCluster`, `ListClusters`, `DeleteCluster`, `UpdateCluster`, `UpdateMaster`
- `SetResourceLabels`, `SetMasterAuth`, `SetNetworkPolicy`, `SetAddonsConfig`,
  `SetLoggingService`, `SetMonitoringService`, `SetLocations`, `SetLegacyAbac`,
  `SetMaintenancePolicy`, `StartIPRotation`, `CompleteIPRotation`
- `CreateNodePool`, `GetNodePool`, `ListNodePools`, `DeleteNodePool`, `UpdateNodePool`
- `SetNodePoolAutoscaling`, `SetNodePoolManagement`, `SetNodePoolSize`,
  `CompleteNodePoolUpgrade`, `RollbackNodePoolUpgrade`
- `GetServerConfig`, `GetJSONWebKeys`, `ListUsableSubnetworks`,
  `CheckAutopilotCompatibility`, `FetchClusterUpgradeInfo`, `FetchNodePoolUpgradeInfo`
- `GetOperation` / `ListOperations`

`ListClusters` and `ListOperations` accept `-` as the location
(`projects/{project}/locations/-`) to list across all zones and regions.

This is the full `container.v1` `ClusterManager` RPC surface except
`CancelOperation` (operations are always synchronous/`DONE`, so there is
nothing in flight to cancel).

The five read-only RPCs above report honest stub data rather than fabricated
analysis, since floci-gcp has no real infrastructure behind them to inspect:

- `GetJSONWebKeys` returns an empty key set — floci-gcp accepts any bearer
  token (no real credential validation), so there is no real signing key to
  expose, and a fabricated one would misleadingly imply verifiable tokens are
  possible.
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

All mutations return a synchronous, `DONE` Operation (no real long-running operation lifecycle) —
consistent across every RPC above, not just cluster create/delete as before. The one exception is
`CompleteNodePoolUpgrade`, which returns `google.protobuf.Empty` (`{}`) as the real API does.

`StartIPRotation`/`CompleteIPRotation` acknowledge the request (bumping the cluster's
fingerprint/etag) rather than performing a real dual-certificate rotation window — there is no
live client traffic to migrate off an old certificate in this emulator.
`CompleteNodePoolUpgrade`/`RollbackNodePoolUpgrade` validate the node pool exists, then return
`{}` and an Operation respectively; there is no real node-version upgrade in flight to complete or
roll back, since node pools don't run real node VMs. `GetServerConfig` is described under
[Versions](#versions).

## Limitations

- Operations resolve synchronously (no real long-running operation lifecycle, no `CancelOperation`).
- Node IAM is not modeled.
- Real Autopilot node management and Compute Engine-backed node scaling (`node_count` read-back —
  see the warning above) are out of scope; floci-gcp does not run real node VMs, so Autopilot mode
  and Fleet registration round-trip as config but are not semantically enforced.
- Cluster-level config blocks the emulator doesn't act on semantically (network policy
  enforcement, binary authorization, private cluster networking, etc.) are stored and echoed back
  verbatim rather than enforced — see the `config`/`autoscaling`/`management` note under Node
  Pools above, which applies at the cluster level too (`extraConfig`).
