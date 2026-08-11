# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[unreleased]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/salatmaster/direnv-jetbrains-plugin/releases/tag/v0.1.0
