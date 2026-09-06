# Spring shadow-inference integration

The Spring Boot service can observe completed queued compatibility checks with the Rust inference
service. This integration is logging-only. The rule engine remains authoritative, and its persisted
`CheckStatus`, breaking changes, warnings, notifications, and API responses do not depend on model
availability or output.

## Integration boundary

`CheckRunner` first executes `ContractEngine.checkCompatibility(...)` and persists that exact result
with `MetadataStore.completeRun(...)`. Only after persistence succeeds does it submit a
`ShadowInferenceObservation` to a bounded daemon executor. A rejected submission, timeout,
connection failure, non-2xx response, or malformed response is logged and is never propagated to
the check runner. There are no retries.

This boundary observes the queued compatibility-check flow. It does not modify the CLI,
evidence-import replay, or strict contract-version write enforcement paths.

## HTTP contract

Spring sends the raw schemas and resolved policy-pack name to the existing Rust endpoint:

```http
POST /v1/shadow/predict
Content-Type: application/json

{
  "base_schema": {"type": "object"},
  "candidate_schema": {"type": "object"},
  "policy_pack": "baseline"
}
```

The response must contain exactly one raw prediction for each frozen seed (`20260826`, `20260827`,
and `20260828`). Each prediction includes its label and SAFE/WARNING/BREAKING probabilities. Spring
does not threshold, aggregate, or otherwise reinterpret them. It computes only one observational
flag: `agreement=true` when **all three** seed labels equal the authoritative label.

The current agreement basis is deliberately boolean for the initial shadow-mode rollout. A 2-of-3
partial disagreement and a 3-of-3 total disagreement both produce `agreement=false`; the flag does
not distinguish their degree. Future analysis must account for that limitation. Every raw per-seed
label and probability remains present in `seed_predictions`, so finer-grained agreement analysis can
still be recovered by reprocessing the structured logs.

## Storage and log schema

Shadow evidence uses the service's existing structured SLF4J application-log convention; no new
database table is introduced. Successful calls emit `event=shadow_inference_prediction` with:

- `role=LOG_ONLY`, observation timestamp, contract ID, run ID, versions, and commit SHA;
- SHA-256 fingerprints for both schema files;
- authoritative label, status, compatibility mode, policy pack, and finding counts;
- `agreement_basis=ALL_THREE`, the agreement flag, and all raw seed predictions/probabilities.

Failed calls emit `event=shadow_inference_call_failed`, retain the authoritative context, set
`agreement=NOT_APPLICABLE`, and record a distinct `failure_stage`, error type, and compact message.
An unexpected observer-dispatch defect emits `event=shadow_inference_dispatch_failed` from the
runner, after the authoritative result has already been persisted.

## Configuration

Shadow inference is disabled by default as a deliberate operational safeguard and must be explicitly
enabled per environment:

```properties
shadow.inference.enabled=${SHADOW_INFERENCE_ENABLED:false}
shadow.inference.endpoint=${SHADOW_INFERENCE_ENDPOINT:http://127.0.0.1:8080/v1/shadow/predict}
shadow.inference.timeout=${SHADOW_INFERENCE_TIMEOUT:500ms}
shadow.inference.worker-threads=${SHADOW_INFERENCE_WORKER_THREADS:2}
shadow.inference.queue-capacity=${SHADOW_INFERENCE_QUEUE_CAPACITY:100}
```

The executor queue is bounded. If it is full, that observation is logged as a dispatch failure and
the authoritative check remains complete and unchanged.

Enabling shadow inference in any environment, including staging or production, must be a conscious
opt-in decision. A routine deployment or configuration synchronization must not enable it
implicitly. Under sustained load while the Rust service is slow or unavailable, at most
`worker-threads` calls can be executing and at most `queue-capacity` observations can wait. The
queue never grows without bound. Once full, the executor rejects each new observation immediately;
Spring logs it with `event=shadow_inference_call_failed` and `failure_stage=DISPATCH`, drops that
shadow observation, and leaves the already-persisted authoritative decision untouched. It does not
retry or block waiting for queue capacity.
