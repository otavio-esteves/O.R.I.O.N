# F0 benchmark harness

`BenchmarkProfile` evidence is JSON validated by the Gradle `check` lifecycle.
Committed evidence belongs in `profiles/`; transient runs belong in `results/` and
are ignored until deliberately reviewed and promoted.

Create a profile by exporting the variables documented in
`docs/benchmark/BenchmarkProfile.md`, then run:

```bash
scripts/benchmark/record-profile.sh benchmark/results/run.json -- candidate-command
./gradlew validateBenchmarkProfiles -PorionBenchmarkProfile=benchmark/results/run.json
```

The example profile is structural evidence only and never represents a measured pass.
