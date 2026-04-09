---
description: "Check GitHub Actions CI/CD build status, test results, and recent workflow runs. Use when verifying builds pass or diagnosing CI failures."
agent: "agent"
tools: [execute, read]
---

Check the CI/CD status for this project:

1. Show recent GitHub Actions workflow runs:
   ```shell
   gh run list --limit 5
   ```
2. If a run failed, get failure details:
   ```shell
   gh run view <run-id> --log-failed
   ```
3. Check the current branch status:
   ```shell
   gh run list --branch "$(git branch --show-current)" --limit 3
   ```
4. If there's an open PR, check its status:
   ```shell
   gh pr checks
   ```
5. Summarize: which workflows passed, which failed, and the root cause of any failures
6. If tests failed, identify the specific test class and method from the logs
