# direnv for JetBrains IDEs

**Your `.envrc` environment, everywhere the IDE runs something.** Run configurations, the build
process, Gradle, Maven, External Tools and the terminal — scoped to the project, reloaded
automatically, and never leaking your secrets.

Implements [IJPL-11588](https://youtrack.jetbrains.com/issue/IJPL-11588), open since 2023 with 83
votes. [direnv-vscode](https://github.com/direnv/direnv-vscode) served as the reference for
direnv's behaviour.

---

## The problem

You use [direnv](https://direnv.net). Your JDK comes from Nix, your toolchain from Devbox, your
credentials from a `.env` file the shell loads for you. In the terminal, everything works.

Then you open the IDE, and none of it exists. So you either copy variables into every run
configuration by hand and keep them in sync forever, or you launch the IDE from a shell where
direnv has already run — and then you can only ever have **one** project open, because that
environment is global to the process.

Both workarounds appear, almost word for word, in the comments on IJPL-11588.

## What this plugin does

| Where | Works |
|---|---|
| Run/Debug configurations — every language | yes |
| Build process (compilation) | yes |
| Gradle sync, Gradle tasks, Maven | yes |
| Terminal | yes |
| External Tools, File Watchers | yes |
| Processes started by other plugins | yes |
| Indexing and static analysis | partly — via SDK suggestion, see [Limitations](#limitations) |

The environment is resolved per **project and working directory**, then injected as each process
starts. Two projects open side by side keep separate environments. A nested `.envrc` in a
subdirectory gets its own. The IDE's own process environment is never modified — that is what makes
the isolation real rather than approximate.

## It keeps itself up to date

Change `flake.lock`, and the environment reloads. Not because the plugin knows anything about Nix,
but because direnv reports every file the environment depends on and the plugin watches all of
them — `.envrc`, `flake.nix`, `.env`, `devbox.json`, whatever your setup uses.

Run `direnv allow` in an ordinary terminal, and the IDE notices within seconds and loads the
environment. No button, no restart.

## Security is the default, not a setting

An `.envrc` is arbitrary shell code, so:

- **`direnv allow` is never invoked automatically** — under any setting, ever. Approval is always an
  explicit action, and the UI offers *Open .envrc* before *Allow*, so reading comes first.
- **Untrusted projects never run direnv at all.** Opening a repository does not execute its code.
- **Variable values never leave memory.** Not into logs, not into run configurations, not into any
  file under `.idea/`. The types that carry environment data refuse to render their own values, and
  a test asserts it with a canary string.

Want to see what was applied? The environment viewer lists variable **names** and whether each was
added, changed or removed. Knowing that `PGPASSWORD` was set is the useful part; its value is not,
and materialising it into a run configuration would put it straight into git.

## Requirements

- A JetBrains IDE, build **261 (2026.1)** or newer — IDEA, PyCharm, GoLand, WebStorm, CLion,
  RubyMine, PhpStorm, RustRover. Verified against IDEA Community and PyCharm Community.
- [`direnv`](https://direnv.net/docs/installation.html) installed and available on `PATH`.

The 2026.1 floor is a hard requirement rather than a preference: it is the first release whose
terminal extension point works correctly across EEL boundaries, and without it the terminal cannot
be supported properly.

## Limitations

Stated plainly, because a plugin that hides these costs you an afternoon:

- **Indexing and static analysis do not follow `PATH`.** The IDE resolves toolchains through
  project SDKs, so a JDK provided by direnv is *offered* as an SDK rather than adopted silently.
  That is deliberate: a Nix store path can vanish after garbage collection, and rewriting your
  project SDK at that moment would break the project with no explanation.
- **Non-local run targets** (Docker, SSH, remote interpreters) bypass the mechanism the plugin
  hooks into.
- **A running Gradle daemon may reuse the previous environment.** The plugin deliberately does not
  stop daemons: the only API is internal, it kills every daemon on the machine including other
  projects', and the problem is unconfirmed. If you hit it, stop the daemon manually and please
  open an issue.
- **WSL is implemented against the documented EEL API but has not been verified on real hardware.**
  Reports and fixes are welcome.
- SDK suggestions currently cover Java. Go, Python and Node.js reuse the same tested resolver and
  need only their product module.

## Building

```bash
./gradlew buildPlugin   # plugin ZIP in build/distributions/
./gradlew test          # direnv is NOT required to run the tests
./gradlew runIde        # sandbox IDE with the plugin installed
```

The build provisions its own JDK 21, so a clean checkout builds without installing anything first.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the module layout, how to add support for another IDE,
and the rules a change must not break. Release notes are in [CHANGELOG.md](CHANGELOG.md).

## License

[Apache-2.0](LICENSE).
