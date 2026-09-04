# direnv Everywhere

**direnv for JetBrains IDEs.**

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
| Git hooks — `pre-commit` and the rest, when you commit from the IDE | yes |
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

Detection combines file system events with a two-second poll of the watched files. The poll is not
redundant: the IDE delivers no events at all for direnv's allow stamps under
`~/.local/share/direnv`, so approval granted in a terminal would otherwise go unnoticed. direnv
itself detects changes the same way, by comparing modification times.

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

## How it compares

Four other direnv plugins are published on the JetBrains Marketplace. The table is built from their
own Marketplace descriptions and, where the source is public, from their code — checked in August
2026 against the versions current then. A dash means the plugin does not offer or document the
capability, not that it was tested and failed. Corrections are welcome as issues.

| | direnv&nbsp;Everywhere | [better_direnv][cmp-bd] | [DirEnv&nbsp;Pro][cmp-pro] | [Direnv][cmp-d] | [Direnv&nbsp;Loader][cmp-dl] |
|---|:--:|:--:|:--:|:--:|:--:|
| **Where the environment lands** | | | | | |
| Run/Debug configurations | every type | six languages ¹ | — | every type | every type |
| Terminal | ✅ | — | — | — | — |
| Build process (compilation) | ✅ | — | — | — | — |
| Gradle sync, Maven import | ✅ | — | — | run configs only | Gradle tasks |
| External Tools, other plugins' processes | ✅ | — | — | — | — |
| A toolchain from direnv offered to the project | Java, Node.js | — | — | — | — |
| **Scope** | | | | | |
| Projects side by side keep separate environments | ✅ | — | — | — | — |
| An `.envrc` below the project root | ✅ | — | root only | by hand ² | — |
| **Staying current** | | | | | |
| Reloads on `flake.lock`, `.env`, … (`DIRENV_WATCHES`) | ✅ | discarded ³ | `.envrc` on save | — | — |
| Notices `direnv allow` run in a terminal | ✅ | — | — | — | — |
| Applies the variables direnv *unsets* | ✅ | — | — | — | — |
| **Security** | | | | | |
| `direnv allow` is never run for you | guaranteed | opt-in auto-allow | — | — | opt-in auto-allow |
| Nothing executes in an untrusted project | ✅ | — | — | — | — |
| Values stay out of run configs and `.idea/` | ✅ | ✅ | — | written in ⁴ | ✅ |
| **Day to day** | | | | | |
| Nothing to switch on per run configuration | ✅ | a checkbox each ⁵ | load by hand | ✅ | a checkbox each ⁵ |
| Current state visible at a glance | status bar, banner | notifications | — | notifications | notifications |
| Applied variables listed by name | ✅ | — | — | with values ⁴ | — |
| Executable, timeout and extra env configurable | ✅ | hardcoded ⁶ | — | executable only | — |

¹ Java, Go, Node.js, Python, PHP and Ruby. Its description notes that "each run configuration type
needs to be added manually", and the source carries one extension class per product — that is the
cost this plugin avoids by hooking the platform below the run configuration instead of above it.
² The root `.envrc` is loaded automatically; any other one is imported through a context-menu action.
³ It deletes `DIRENV_WATCHES` from the exported environment, so nothing outside `.envrc` — a
`flake.lock`, a `.env`, an allow stamp — can trigger a reload.
⁴ By design: values are merged into the Environment Variables field of every run configuration,
where they are visible and editable — and saved with the configuration.
⁵ Per run configuration, so a newly created one starts without the environment until you remember.
⁶ It invokes `direnv` by name, with no setting to point at another binary; its per-configuration
settings are the two checkboxes above.

**What they do that this plugin does not.** better_direnv registers `.envrc` as a shell file type,
so the file itself gets syntax highlighting; here that is left to the Shell Script plugin. It has
also been maintained far longer than this one.

[cmp-bd]: https://plugins.jetbrains.com/plugin/19275-better-direnv
[cmp-pro]: https://plugins.jetbrains.com/plugin/28160-direnv-pro
[cmp-d]: https://plugins.jetbrains.com/plugin/30539-direnv
[cmp-dl]: https://plugins.jetbrains.com/plugin/30187-direnv-loader

## Requirements

- A JetBrains IDE, build **261 (2026.1)** or newer — IDEA, PyCharm, GoLand, WebStorm, CLion,
  RubyMine, PhpStorm, RustRover. Verified against IDEA Community and PyCharm Community.
- [`direnv`](https://direnv.net/docs/installation.html) installed and available on `PATH`.

The 2026.1 floor is a hard requirement rather than a preference: it is the first release whose
terminal extension point works correctly across EEL boundaries, and without it the terminal cannot
be supported properly.

## Limitations

Stated plainly, because a plugin that hides these costs you an afternoon:

- **The environment has to be loaded already.** Injection reads what is cached and never starts
  direnv itself, so a process launched before the first load finishes — or in a project you have
  not trusted, or whose `.envrc` is still blocked — starts without it, silently. Opening the
  project triggers the load, so in practice the cache is warm long before you run anything.
- **Git hooks run only if the IDE is told to run them.** The commit options carry a *Run Git hooks*
  checkbox; with it off no hook runs at all, and no environment can reach one. That is the IDE's
  setting rather than this plugin's, but it is the first thing to check when a hook does not see
  what you expect.
- **Indexing and static analysis do not follow `PATH`.** The IDE resolves toolchains through its
  own settings, so a JDK or a Node interpreter provided by direnv is *offered* rather than adopted
  silently.
  That is deliberate: a Nix store path can vanish after garbage collection, and rewriting your
  project SDK at that moment would break the project with no explanation.
- **Non-local run targets** (Docker, SSH, remote interpreters) bypass the mechanism the plugin
  hooks into.
- **Variables direnv *unsets* are not removed from Gradle builds.** Gradle receives its
  environment through a settings API that can only add variables on top of the IDE's own, so an
  unset is a no-op on that one path. Everywhere else — run configurations, the terminal, the build
  process — unsets are honoured. A warm Gradle daemon is not a problem: the environment is handed
  to it explicitly with every build, so it cannot go stale between builds.
- **WSL and remote projects run direnv on the machine the project lives on, but this has not been
  verified on real hardware.**
- Toolchain suggestions cover Java and Node.js. Go and Python reuse the same tested resolver and
  need only their product module, plus an IDE that bundles the language to compile it against.

## When the environment does not arrive

A process starting without the direnv environment looks exactly like a process starting with it, so
the plugin explains itself rather than leaving you to guess. Turn its logging on in **Help →
Diagnostic Tools → Debug Log Settings** by adding

```
io.github.salatmaster.direnv
```

then reproduce the problem and open **Help → Show Log in Finder**. Every process the IDE starts
leaves one line: either how many variables were injected, or why none were.

| The log says | What it means |
| --- | --- |
| `no environment is loaded for it` | direnv has not run for that directory. Check the status bar; if it shows nothing, the project may have opened before direnv finished. |
| `no open project contains it` | the working directory lies outside every content root of every open project. The plugin will not guess which project's environment to use, because guessing wrong leaks one project's secrets into another. |
| `direnv is off or the project is untrusted` | either the plugin is disabled under Tools → direnv, or the project has not been trusted — an `.envrc` is arbitrary shell code, so untrusted projects never run one. |
| `the command line has no working directory` | the process was started without one, and there is nothing to resolve an environment against. |

Those lines are the useful part of a bug report. They name no variables and no values: direnv output
is routinely secret, and even a name can disclose which service a project talks to.

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
