# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[unreleased]: https://github.com/salatmaster/direnv-jetbrains-plugin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/salatmaster/direnv-jetbrains-plugin/releases/tag/v0.1.0
