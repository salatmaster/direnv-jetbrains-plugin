# Contributing

Thanks for considering a contribution. This document assumes no prior knowledge of the codebase.

## Building and testing

```bash
./gradlew build          # compile and run tests
./gradlew test           # tests only
./gradlew buildPlugin    # produce the plugin ZIP in build/distributions/
./gradlew verifyPlugin   # check compatibility with target IDEs
./gradlew runIde         # launch a sandbox IDE with the plugin installed
```

The build provisions its own JVM 21 toolchain, so you do not need a specific JDK installed. The
first build downloads the IntelliJ Platform and takes a few minutes.

`direnv` does **not** need to be installed to build or test: the test suite drives a fake process
runner. Install it if you want to exercise the plugin by hand in `runIde`.

## Module layout

| Module | Depends on | Contains |
|---|---|---|
| `core` | the platform only | direnv CLI, environment model, cache, watching, injection, UI |
| `products/terminal` | terminal plugin | terminal environment injection |
| `products/java` | Java plugin | JDK suggestion |

Gradle project paths match directory paths, so `products/terminal` is the project
`:products:terminal`. Everything is compiled into a single jar; the split exists for one reason
only, and it is a good one: `core` is compiled without any language plugin on its classpath, so it
*cannot* accidentally use a class from the Java plugin and break the plugin in PyCharm. Verification
runs against PyCharm Community precisely to keep that honest.

Each product module owns its own `META-INF/direnv-<name>.xml`, next to the code it registers. The
separate file is not a style choice: `<depends optional="true" config-file="...">` is the only way
the platform lets an extension register conditionally, and without it the Java extension would be
registered in PyCharm too and fail to load.

### Adding support for another IDE

Most of the time no new code is needed: the environment reaches processes through
`commandLineEnvCustomizer`, which is language-agnostic. A product module is needed only for
toolchain suggestions or IDE-specific behaviour. To add one:

1. Create `products/<name>` with a `build.gradle.kts` modelled on `products/java`.
2. Add it to `settings.gradle.kts` and as a `pluginComposedModule` in the root `build.gradle.kts`.
3. Add `products/<name>/src/main/resources/META-INF/direnv-<name>.xml` with your extensions.
4. Reference it from the root `plugin.xml` as
   `<depends optional="true" config-file="direnv-<name>.xml">`.

For a toolchain suggestion, reuse `ToolchainCandidateResolver`: it already handles the home
variable, the `PATH` fallback, and verifying that the path still exists.

## Rules that are not negotiable

These come from the plugin's security model. A change that breaks one will not be merged.

- **Never run `direnv allow` without an explicit user action.** An `.envrc` is arbitrary shell code.
- **Never run direnv in an untrusted project.** Go through `DirenvGuard`.
- **Never log or persist environment values.** Log names and counts. `DirenvEnvironment.toString()`
  hides both by design — do not add a way around it, and do not add a field to `DirenvSettings`
  capable of holding direnv output.
- **Never block the EDT.** `DirenvCommandLineEnvCustomizer` runs synchronously at process start and
  serves cache only; loading happens in coroutines.

## Testing expectations

Logic that can be tested without the platform should be: the codebase deliberately separates pure
logic (parsing, path matching, presentation rules) from the platform glue for that reason.

If you touch anything that handles environment values, add a test asserting the value does not
appear where it should not. `DirenvEnvironmentTest` and `DirenvServiceTest` have examples using a
unique canary string.

Note that `BasePlatformTestCase` reuses one light project across tests in a class, so project-level
services survive between tests. Reset them in `setUp` and `tearDown`, as the existing tests do.

## Commits and pull requests

Explain why a change is needed, not only what it does. If you worked around a platform behaviour,
say which one — that context is what makes the code maintainable later.
