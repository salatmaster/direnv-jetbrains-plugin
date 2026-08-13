package io.github.salatmaster.direnv

/**
 * The file direnv reads, by name.
 *
 * direnv resolves exactly this name and no variant of it: `.envrc.local` and friends exist only
 * because an `.envrc` sources them, and the plugin must not claim them either. The name is also
 * written into `plugin.xml`, which cannot reference Kotlin — change it in both places or not at all.
 */
const val ENVRC_FILE_NAME = ".envrc"
