# direnv for JetBrains IDEs

Loads the environment produced by [direnv](https://direnv.net) and makes it available to processes
the IDE starts — run/debug configurations, the build process, Gradle and Maven, External Tools and
the terminal — scoped to the project and the working directory.

Implements [IJPL-11588](https://youtrack.jetbrains.com/issue/IJPL-11588).
[direnv-vscode](https://github.com/direnv/direnv-vscode) was used as the reference implementation
for direnv's behaviour.

## Status

Under active development. Working today:

- the environment loads when a project opens;
- it is injected into processes the IDE starts, including run/debug, the JPS build process,
  Gradle sync and Maven, and External Tools;
- it is applied to terminal sessions;
- it reloads automatically when any file direnv depends on changes — not only `.envrc`, but also
  `flake.nix`, `flake.lock`, `.env`, and direnv's own allow/deny stamps, so a `direnv allow` typed
  in an external terminal is picked up;
- the status bar shows whether an environment is active, blocked or failing, with actions to
  reload, open, allow or block, and a viewer listing the applied variable names;
- `.envrc` files are never approved automatically, and untrusted projects never run direnv.

Not implemented yet: SDK and toolchain detection, Gradle daemon invalidation, and release
automation. That is milestone 3.

## How it works

One extension point does most of the work. `com.intellij.commandLineEnvCustomizer` is invoked from
`GeneralCommandLine.setupEnvironment()`, and nearly everything the IDE launches goes through
`GeneralCommandLine` — including the JPS build process and, via `LocalTargetEnvironment`, Gradle
sync and Maven. That is why this plugin does not need per-runner support for each language.

The terminal is the exception: it starts its shell through the EEL API or `PtyProcessBuilder`,
bypassing `GeneralCommandLine`, so it is handled separately through
`org.jetbrains.plugins.terminal.shellExecOptionsCustomizer`.

The environment is never applied to the IDE process itself. It is resolved per (project, working
directory) and injected at process start, so several open projects keep separate environments and
nested `.envrc` files work without special handling.

## Requirements

- A JetBrains IDE, build 261 (2026.1) or newer.
  This is a hard requirement, not a preference: `shellExecOptionsCustomizer` first appears in 261
  and is the only terminal hook that works correctly across EEL boundaries.
- `direnv` installed and available on `PATH`. The plugin does not bundle it.

## Security

An `.envrc` is arbitrary shell code, so:

- the plugin never runs `direnv allow` on your behalf, under any setting;
- it does not run direnv at all in a project you have not trusted;
- environment values are never written to logs, to run configurations, or to any file under
  `.idea/`. The cache is in memory only, and the types that carry environment data refuse to render
  their values.

One caveat outside the plugin's control: the platform itself logs terminal environment variables
when debug logging is enabled for the terminal category.

## Known limitations

- **Indexing and static analysis do not use the process `PATH`.** IntelliJ resolves toolchains
  through project SDKs and interpreters, so making `direnv`-provided tools visible to indexing
  requires configuring an SDK. Milestone 3 will offer to do that; a plugin cannot make indexing
  follow `PATH` directly.
- **A running Gradle daemon may be reused with the previous environment.** Whether this actually
  happens depends on the Gradle version: the Tooling API passes environment variables per build,
  and Gradle forks a new daemon when they differ. The plugin deliberately does not stop daemons —
  the only API for it is internal, stops every daemon on the machine including other projects', and
  the problem is not confirmed. If you hit a stale environment in Gradle, stop the daemon manually
  and please open an issue describing the setup.
- **Plugin logic that never launches a process cannot see the environment.**
- **WSL support is written against the documented EEL API but has not been verified on a real
  Windows machine.** Reports and fixes are welcome.

## Building

```bash
./gradlew buildPlugin
```

The plugin ZIP is written to `build/distributions/`. Tests: `./gradlew test`. Compatibility check:
`./gradlew verifyPlugin`.

The build provisions its own JDK 21 toolchain, so a clean checkout builds without installing a
specific JDK first.

## Contributing

Issues and pull requests are welcome. The codebase is organised as a core module that depends only
on the platform, plus optional per-product modules, so support for another IDE can be added without
touching the core.

## License

Apache-2.0. See [LICENSE](LICENSE).
