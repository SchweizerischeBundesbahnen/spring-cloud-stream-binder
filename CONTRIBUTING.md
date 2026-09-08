## How to contribute to this project

#### **Did you find a bug?**

* **Ensure the bug was not already reported** by searching on GitHub under [Issues](https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/issues).

* If you're unable to find an open issue addressing the problem, [open a new one](https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/issues/new). Be sure to include a **title and clear description**, as much relevant information as possible, and a **code sample** or an **executable test case** demonstrating the expected behavior that is not occurring.

#### **Did you write a patch that fixes a bug?**

* Open a new GitHub pull request with the patch.

* Ensure the PR description clearly describes the problem and solution. Include the relevant issue number if applicable.

#### **Do you intend to add a new feature or change an existing one?**

* Open a GitHub [enhancement request issue](https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/issues/new) and describe the new functionality.

#### **How should a commit message look?**

We follow [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/):

```
<type>[(<scope>)][!]: <why this change exists, in one line>
```

* **The type is mandatory**, and is one of `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `build`, `ci`, `chore`, `revert`. Dependency updates are `build(deps):`, which is what our bots write.
* **The description is imperative present tense**, starts lowercase, ends without a full stop, and stays under 72 characters: `stop the mapper losing an XML-content payload`, not `Fixed the mapper.`
* **The message says why, not what.** The diff already records what changed. If a sentence would still be true with the diff deleted, cut it.
* **A scope is optional.** `examples` is the only one this repository uses, for changes under [`examples/`](examples/README.md); the binder itself takes no scope, because it would only repeat the repository name.
* **A change that breaks a consumer carries `!` before the colon and a `BREAKING CHANGE:` footer** saying what breaks and how to migrate. This is a library other teams build on, so a silent breaking change becomes somebody else's incident.
* Changes made at SBB carry the Jira key immediately after the colon — `fix: STTRS-1234 …`. A contribution from outside SBB has no ticket and needs no key.

History is not rewritten to match: the convention starts where it was adopted, and `git log` straddles two styles before that point.

#### **Do you have questions about the source code?**

* Open a [question issue](https://github.com/SchweizerischeBundesbahnen/spring-cloud-stream-binder/issues/new) on this repository. This binder is an independent fork; questions about it are not answered by Solace.
* For questions about the Solace PubSub+ broker or the JCSMP API themselves, consult the [Solace product documentation](https://docs.solace.com).
