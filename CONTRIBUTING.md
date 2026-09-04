# Contributing to O.R.I.O.N.

The normative engineering rules are in `AGENTS.md`. The default contribution flow is:

```text
Issue
→ dedicated branch
→ implementation
→ relevant tests
→ pull request
→ required CI green
→ merge
```

Use the engineering Issue form as the implementation contract and keep one primary
responsibility per branch and pull request. Direct pushes to `main` are not the normal
development workflow.

## GitHub main protection

Repository administrators must protect `main` with a branch ruleset or equivalent
branch-protection rule configured as follows:

- target the default branch `main`;
- require a pull request before merging;
- require the `docker-build-and-check` status check from the `CI` workflow;
- require branches to be up to date before merging;
- block merging while required checks are pending or failing;
- block direct updates to `main` for normal contributors;
- retain an explicit repository-administrator bypass for legitimate emergencies.

This policy is configured in GitHub repository settings, not by a trusted local file.
After changing the ruleset or the workflow job name, verify the required status-check
binding in GitHub.
