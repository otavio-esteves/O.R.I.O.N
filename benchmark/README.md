# F0 benchmark harness

`BenchmarkProfile` evidence is JSON validated against its canonical JSON Schema by
the Gradle `check` lifecycle.
Committed evidence belongs in `profiles/`; transient runs belong in `results/` and
are ignored until deliberately reviewed and promoted.

Create a profile by exporting the variables documented in
`docs/benchmark/BenchmarkProfile.md`, then run:

```bash
scripts/benchmark/record-profile.sh benchmark/results/run.json -- candidate-command
```

The recorder validates before atomic publication and refuses to overwrite an
existing result. Its `executionResult` describes command execution only;
qualification remains `NOT_EVALUATED` until an identified criteria set is applied.
The example profile is structural evidence only and never represents a measured or
qualified pass.
