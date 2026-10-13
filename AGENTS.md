# Repository instructions

Read [README.md](README.md) for setup, device requirements, and app behavior.
Read [docs/wallet-analysis.md](docs/wallet-analysis.md) before changes to wallet models, image sources, or cache writes.

- Keep the app small: Kotlin, Compose Material 3, and libsu.
- Keep user-facing text in Chinese. Write repository documentation in English.
- Use the pinned tools and tasks in [mise.toml](mise.toml).
- Run `mise run check` after meaningful code changes.
- Run `mise run alint` after repository-structure or documentation changes.
- Run `mise run format` to format Kotlin source.
- Read wallet-provider metadata to enumerate visible swiping cards. Keep cards with unavailable images visible.
- Match each card type's actual image model and source to cache entries. Do not infer identities from cache filenames.
- Keep custom URL and plain URL cache identities separate. Only a proven swiping-screen target permits replacement.
- Disable independent replacement for shared cache entries. Treat native wallet drawables as unsupported.
- Preserve the first original backup, image dimensions, owner, mode, and SELinux context during replacements.
- Keep root work outside the UI thread. Report root command failures to the user.
- Validate selected cache paths before root mutations.
- Keep device screenshots, card images, balances, and root-manager data out of version control.
- Use synthetic images for tests and examples.
