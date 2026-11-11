# Security Token Service (STS)

floci-gcp implements the OAuth 2.0 token-exchange endpoint used by Google
`DownscopedCredentials` and by Workload Identity Federation for GKE. It exchanges
a source access token and a Credential Access Boundary (CAB) for an
emulator-managed downscoped access token, and a GKE cluster's Kubernetes
ServiceAccount token for a federated access token
([STS `token` reference](https://docs.cloud.google.com/iam/docs/reference/sts/rest/v1/TopLevel/token)).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_GCP_SERVICES_STS_ENABLED` | `true` | Enable/disable STS token exchange |

## Endpoint

| Method | Path | Content type |
|---|---|---|
| `POST` | `/v1/token` | `application/x-www-form-urlencoded`, or `application/json` with snake_case or camelCase field names |
| `POST` | `/v1/identitybindingtoken` | the same; the legacy `securetoken.googleapis.com` path Google's Secret Manager CSI provider uses for GKE tokens |

No `Authorization` header is read. The downscoping exchange requires these fields:

| Field | Required value |
|---|---|
| `grant_type` | `urn:ietf:params:oauth:grant-type:token-exchange` |
| `subject_token_type` | `urn:ietf:params:oauth:token-type:access_token` |
| `requested_token_type` | `urn:ietf:params:oauth:token-type:access_token` |
| `subject_token` | The source access token |
| `options` | A JSON-encoded GCS Credential Access Boundary |

Validation failures use the OAuth error fields `error` and
`error_description`.

## Workload Identity Federation for GKE

With `subject_token_type=urn:ietf:params:oauth:token-type:jwt`, the subject token is a Kubernetes
ServiceAccount token of a floci-gcp GKE cluster and `audience` is
`identitynamespace:PROJECT_ID.svc.id.goog:https://container.googleapis.com/v1/projects/PROJECT/locations/LOCATION/clusters/CLUSTER`,
the form Google's Secret Manager CSI provider and fleet credential files send
([Fleet Workload Identity](https://docs.cloud.google.com/kubernetes-engine/fleet-management/docs/use-workload-identity)).
`scope` is accepted and not checked. The token is verified as the STS reference describes a JWT subject
token: a `kid` and an `RS256` or `ES256` `alg`, a signature by one of the cluster's keys (the keys its API
server publishes, read again once if the `kid` is unknown), `iss` equal to the cluster's issuer, `aud`
naming the pool, `exp` in the future, `iat` in the past and no more than 24 hours old; the subject must be
`system:serviceaccount:NAMESPACE:KSA`, and the cluster must have that workload pool. The answer is a
`floci-gcp-federated-` token valid for 3600 seconds, whose principal is
`principal://iam.googleapis.com/projects/NUMBER/locations/global/workloadIdentityPools/PROJECT_ID.svc.id.goog/subject/ns/NAMESPACE/sa/KSA`
(see [IAM](iam.md#gke-workload-identity-principals) and [GKE](gke.md#workload-identity-federation-for-gke)).

Token problems answer `400` with `error=invalid_grant`; a missing, malformed or unknown audience, and
workload identity pool provider audiences (`//iam.googleapis.com/...`, not emulated), answer `400` with
`error=invalid_request`. Google's exact error codes and descriptions for each case are not verified.

## Credential Access Boundary support

The CAB parser supports Cloud Storage bucket resources and these permissions:

- `inRole:roles/storage.legacyObjectReader`
- `inRole:roles/storage.objectViewer`
- `inRole:roles/storage.legacyBucketWriter`

`availabilityCondition` is optional. Without it, the rule applies to the whole
bucket. When present, the condition can restrict access to one object prefix
using `resource.name.startsWith(...)`,
`api.getAttribute('storage.googleapis.com/objectListPrefix', '').startsWith(...)`,
or both expressions joined with `||`. Both expressions must identify the same
prefix.

## Token lifetime

Tokens exchanged from arbitrary external source credentials have a one-hour
lifetime. When the source is an unexpired Floci-issued IAM Credentials
impersonated token, the new token inherits that source token's expiration time
and service-account principal. A subsequent GCS object request must satisfy
both the CAB and that principal's bucket IAM policy when IAM enforcement is
enabled. Unknown or expired Floci-issued source tokens are rejected with
`invalid_grant`.

## Scope and deviations

- Only access-token-to-access-token exchange and the GKE Workload Identity JWT exchange are implemented;
  generic Workload Identity Federation (OIDC and SAML providers of workload identity pools) is not.
- Only the documented GCS CAB resource, permission, and prefix-expression
  subset is accepted.
- Non-floci source credentials are not validated, matching the emulator's
  general credential-bypass behavior.
- Recursive downscoping is not supported. A downscoped token issued by
  floci-gcp cannot be used as the source for another exchange and is rejected
  with `invalid_grant`. The emulator does not intersect the existing and
  requested boundaries, so replacing the existing boundary could broaden the
  token's authority. The exact Google STS behavior and error for this request
  have not been verified.
- A downscoped token minted by floci-gcp is enforced only for GCS requests:
  the request must match one of the token's CAB rules. Other credentials,
  including Floci-issued OAuth and impersonated tokens, remain accepted without
  credential validation.
- STS has no separate IAM-policy surface. CAB is an upper bound on the source
  credential's GCS authority, not an authorization policy for token exchange.
