package dev.pnptracker.platform.files

/**
 * Names the XDG layout for a test whose subject is not the layout.
 *
 * [AppPathsResolver] chooses between two layouts by the system it is running on
 * (PLAN 14.8.1), so a test that hands it `XDG_…` variables and lets it read the
 * real `os.name` would be handed the Windows layout on a Windows runner — and
 * would resolve to a real user's folders, because the Windows layout does not
 * read those variables. Saying which layout is meant keeps such a test about its
 * own subject and away from anybody's data (PLAN 14.8.5).
 *
 * `user.home` is still read from the machine, because that is what the tests
 * that leave a base directory unset have always used.
 */
internal val XDG_LAYOUT: (String) -> String? = { name ->
    if (name == "os.name") "Linux" else System.getProperty(name)
}

/** The same, for a test that means the Windows layout of PLAN 14.8.1. */
internal val WINDOWS_LAYOUT: (String) -> String? = { name ->
    if (name == "os.name") "Windows 11" else System.getProperty(name)
}
