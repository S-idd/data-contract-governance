# Synchronous model-comparison demo cases

The cases below are stable records in the checked-in Kubernetes fixture
`../data/external/kubernetes-openapi-v1/scored-accepted-v1/kubernetes-accepted-external-manifest-v3.json`.
`scripts/demo/run-sync-model-comparison.sh` extracts each pair to a temporary
directory, calls the presentation CLI, and removes those temporary files on exit.

Build the CLI once and start the Rust and Spring services as described in the
main demo plan. Then use these exact commands:

```bash
./scripts/demo/run-sync-model-comparison.sh http-ingress-path
./scripts/demo/run-sync-model-comparison.sh pod-failure-policy-rule
```

| Command case | Fixture record | Verified result |
|---|---|---|
| `http-ingress-path` | `kubernetes.prescreen.v1.21.0-to-v1.22.0.io.k8s.api.networking.v1.HTTPIngressPath` | Rule engine `BREAKING`; all three model seeds `BREAKING`; `AGREE`. |
| `pod-failure-policy-rule` | `kubernetes.prescreen.v1.28.0-to-v1.29.0.io.k8s.api.batch.v1.PodFailurePolicyRule` | Rule engine `SAFE`; all three model seeds `BREAKING`; `DISAGREE`. |

`HTTPIngressPath` is retained as the requested regression case, but the frozen
V9 service demonstrably returns `BREAKING` for all three seeds. It cannot be
presented truthfully as a disagreement. `PodFailurePolicyRule` is the verified
replacement disagreement case.
