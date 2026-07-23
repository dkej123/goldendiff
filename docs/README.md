# Golden Diff documentation

📖 **The end-user documentation is published as a site:
[dkej123.github.io/goldendiff](https://dkej123.github.io/goldendiff/)** (built from this folder with
[just-the-docs](https://just-the-docs.com/) via GitHub Pages).

## User guide (published site)
- [Home](index.md)
- [Installation](installation.md)
- [Getting started](getting-started.md)
- [Matching goldens](matching.md)
- [Comparing screenshots](comparing.md)
- [Settings reference](settings-reference.md)
- [Troubleshooting & FAQ](troubleshooting.md)
- [images/](images/) — screenshot placeholders and the capture checklist

## Contributor docs (repo only, not on the site)
- [architecture.md](architecture.md)
- [build-and-run.md](build-and-run.md)
- [features.md](features.md)
- [gotchas.md](gotchas.md)
- [roadmap.md](roadmap.md)
- [marketplace.md](marketplace.md)
- [privacy.md](privacy.md)
- [amplitude-setup.md](amplitude-setup.md)
- [sentry-setup.md](sentry-setup.md)

## Publishing the site
The site is built by GitHub Pages with no custom CI. To (re)enable it: repo **Settings → Pages →
Build and deployment → Deploy from a branch → `main` / `docs`**. Configuration lives in
[`_config.yml`](_config.yml); contributor docs above are excluded from the build there.
