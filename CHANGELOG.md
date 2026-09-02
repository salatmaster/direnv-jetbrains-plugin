# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- direnv is found in WSL and on remote hosts. The Eel API takes an absolute path to the binary on
  the machine it starts it on and resolves nothing against `PATH` the way starting a local process
  does, so the default setting — plain `direnv` — could not work there at all. The binary is now
  looked up on that machine, both the way the IDE's own connection to it sees `PATH` and the way a
  login shell does, which is the difference that matters on NixOS, where direnv lives under
  `/run/current-system/sw/bin`. A path written either way is accepted: `/usr/bin/direnv` as that
  machine writes it, or `\\wsl.localhost\NixOS\...` as this one does.
- direnv runs with the environment a shell on that machine would start in. It evaluates `.envrc`
  with bash, and that file reaches for nix, devbox or devenv, none of which has to be present in
  the environment the IDE's connection happened to inherit. `DIRENV_*` is withheld from it: a
  direnv user has direnv hooked into their login shell, and handing those variables back would have
  direnv export a diff against some other directory's state, or decide there was nothing to do.
- A failure to start direnv names the working directory as well as the executable, instead of
  reporting every `ENOENT` as a missing binary. That error covers both, the working directory is
  what it actually was in 0.2.0, and the message is what sent the report behind this release
  through six settings values in a row. The log now also records which machine direnv will run on.
- Every path direnv reports is read the way the machine that wrote it meant it. `DIRENV_FILE` and
  the watch list were being read with this JVM's rules, and on Windows that turns a WSL project's
  `/home/u/p` into a drive-relative `C:\home\u\p` instead of failing — the same trap as 0.2.1's
  working directory, one layer further in. It cost a WSL project a reload every two seconds for as
  long as it stayed open, because every watched file looked to the poll like a file that had just
  been deleted, and it left *Allow This .envrc* naming a file direnv could not find.
- A terminal opened in a WSL project gets the environment. Its working directory arrives written in
  that machine's syntax, and the fallback meant to cover exactly that case was unreachable: reading
  the path the wrong way produced a path rather than an error, so nothing looked like a failure.

## [0.2.2] - 2026-09-01

### Fixed

- Allowing an `.envrc` clears the warning above it. The banner is an editor notification, and the
  platform caches those until something asks for a recompute; nothing did, so the warning stayed on
  screen until the file was closed and reopened — the plugin contradicting itself at the moment the
  user acted on it.
- *Show direnv Environment* opens wide enough to read. The table was handed to the dialog with no
  preferred size, so it shrank to its minimum and clipped every name: `PGPASSWORD` arrived as
  `PGPASSW…`, which defeats the point of a window whose whole job is to say which variables were
  applied. The status column is pinned narrow, the rest of the width goes to the name, and the size
  is remembered once dragged.

## [0.2.1] - 2026-09-01

### Fixed

- direnv runs on the machine the project lives on. A project in WSL or on a remote host was handled
  as though its files were local: `project.basePath` is written in that machine's path syntax, and
  `Paths.get("/home/u/project")` on Windows yields a drive-relative `C:\home\u\project` rather
  than failing, so direnv was started as a Windows process in a directory that never existed. The
  project directory is now translated through the platform's own mapping, and direnv is started over
  there, where both the binary and the path make sense. Projects on the local machine keep the code
  path they always had.

## [0.2.0] - 2026-08-31

### Added

- The Node interpreter that direnv provides is offered to the project, the way the JDK already was.
  Injecting `PATH` into launched processes was never enough for Node: the IDE resolves the
  interpreter from its own settings, so the JavaScript Runtime page, inspections and the
  `package.json` tooling all reported Node as missing while a terminal in the same project found it.
  Offered rather than applied, because a Nix store path can vanish after garbage collection. Only in
  IDEs that bundle JavaScript support, which excludes IDEA Community.
- The plugin says why a process did not receive the environment, instead of failing silently. There
  are four separate reasons for it and none of them used to reach the log, so a report of "my run
  configuration sees nothing" could not be told apart from any of the others. Turning on the
  `io.github.salatmaster.direnv` log category now produces one line per process the IDE starts,
  naming either the number of variables injected or the reason there were none. Variable names and
  values both stay out of it.

### Fixed

- An environment is found by a lookup for the very directory it is filed under. Environments are
  filed under the directory holding the `.envrc`, but a lookup began its search at the *parent* of
  the directory asked about, so that one directory could miss its own environment. It stayed hidden
  because the first load is normally triggered for the project root, which makes the two the same
  directory; a first load triggered from a subdirectory — by a build tool, say — is what exposes it.

## [0.1.7] - 2026-08-13

### Added

- `.envrc` has an icon of its own, so it stands out among the shell scripts in a project rather than
  looking like one more of them. The file is still a Shell Script — it keeps its highlighting, and
  the Marketplace still knows to offer this plugin to whoever opens one without it.

## [0.1.6] - 2026-08-12

### Added

- The plugin has a logo, in a light and a dark variant. Until now the Plugins dialog and the
  Marketplace listing showed the platform's placeholder, which is what a plugin looks like when it
  never supplied one.

## [0.1.5] - 2026-08-12

### Added

- `.envrc` is recognised as a shell script, so it gets syntax highlighting instead of opening as
  plain text. Nothing in the IDE claimed the name before. It also gets the rest of the IDE's shell
  support, which includes the offer to install shellcheck the first time one is opened — that
  prompt comes from the Shell Script plugin, not from this one, and declining it changes nothing.
- An IDE without this plugin now offers it when an `.envrc` is opened. The Marketplace builds that
  suggestion from the file names a plugin declares, and this one declared none.
- Contributors can get the build toolchain from Nix, with `nix develop` or by allowing the `.envrc`
  in the repository root. Entering the shell also installs the repository's git hooks, which check
  the Nix files only for now.

### Fixed

- `gradle.properties` records the version that was released. Nothing kept `pluginVersion` current,
  so it sat at 0.1.0 across four releases and a local `./gradlew buildPlugin` produced an artifact
  numbered 0.1.0 — carrying 0.1.0's change notes, once those began coming from the changelog. The
  release workflow now writes it in the same commit that cuts the changelog.
- Two properties nothing read are gone. `pluginName` still said `direnv` long after the plugin was
  renamed to direnv Everywhere, which is what a value no code consults does; the name comes from
  `plugin.xml`. `pluginRepositoryUrl` was unreferenced too.

### Security

- Every GitHub Actions step that builds and publishes a release is pinned to a commit SHA instead
  of a tag, so an action that is retagged or compromised upstream cannot change what goes into a
  published build.

## [0.1.4] - 2026-08-11

### Fixed

- The Marketplace shows what changed in a version. `<change-notes>` was never set, so *What's new*
  was blank for every release from 0.1.0 to 0.1.3, both on the plugin page and in the Plugins
  dialog when an update is offered. It now carries the changelog entry for the version being
  released. Only future versions can gain it: the Marketplace does not allow the notes of an
  update to be edited once it has been submitted.

### Changed

- Release notes on GitHub quote the changelog instead of listing merged pull request titles, which
  described what landed rather than what changed for anyone installing the plugin.

## [0.1.3] - 2026-08-11

### Changed

- Released archives are signed. Installing an unsigned plugin makes the IDE warn that the author
  cannot be verified; that warning is gone, for the archive attached to the GitHub release as well
  as the one on the Marketplace.

## [0.1.2] - 2026-08-11

### Fixed

- The status bar menu works. Every entry in it was disabled and *Allow This .envrc* was missing
  altogether: the popup was built without a data context, so the actions could not resolve the
  project they belong to, and the one that hides itself when unavailable hid itself always. The
  same actions under Tools → direnv were never affected.

- A revoked approval is reported as revoked. `direnv deny` exits successfully and exports an empty
  environment, which is exactly what an `.envrc` that sets no variables looks like, so the status
  bar read *loaded, no variables changed* moments after approval was withdrawn. direnv lists its
  own deny stamp in `DIRENV_WATCHES` and that stamp exists only while the file is denied, so the
  state is now read from there — at no extra cost, since that watch list was already being parsed.

- *Allow This .envrc* is greyed out while the file is already approved, where it used to re-run
  direnv and look like nothing had happened, and *Block This .envrc* is greyed out when there is no
  `.envrc` to revoke, where it used to offer itself and silently do nothing. Both stay visible
  rather than disappearing: an entry that comes and goes shifts every row below it, and in this
  menu that would put *Block* where the pointer was aimed at *Show*.

## [0.1.1] - 2026-08-11

### Changed

- Renamed the plugin to **direnv Everywhere**. The Marketplace rejects an upload whose
  name collides with an existing listing, and four direnv plugins are already published.
  The new name also says what distinguishes this one: the environment reaches the build
  process, Gradle, Maven and the terminal, not only run configurations.

## [0.1.0] - 2026-08-11

### Added

- Loads the direnv environment when a project opens, scoped to the project and working directory.
  Several open projects keep separate environments, and nested `.envrc` files are handled.
- Injects the environment into processes the IDE starts: run/debug configurations of every
  language, the JPS build process, Gradle sync and Maven, External Tools, and processes started by
  other plugins.
- Applies the environment to terminal sessions.
- Reloads automatically when any file the environment depends on changes. The watch set comes from
  direnv's own `DIRENV_WATCHES`, so `flake.nix`, `flake.lock`, `.env` and `devbox.json` are covered,
  as are direnv's allow/deny stamps — meaning a `direnv allow` typed in an external terminal is
  picked up by the IDE.
- Status bar entry showing whether the environment is active, blocked or failing, with actions to
  reload, open, allow and block, plus a viewer listing which variables were applied.
- Banner over a blocked `.envrc` offering approval at the moment the file is on screen.
- Settings page under Tools → direnv.
- Offers the JDK provided by direnv when it differs from the project SDK.
- Notices `direnv allow` run in an external terminal and loads the environment without any action
  in the IDE.

### Security

- `direnv allow` is never invoked automatically, under any setting.
- direnv is not executed at all in projects that have not been trusted.
- Environment values are never written to logs, run configurations, or any file under `.idea/`.

[unreleased]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.2.2...HEAD
[0.2.2]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.7...v0.2.0
[0.1.7]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/salatmaster/direnv-jetbrains-plugin/releases/tag/v0.1.0
