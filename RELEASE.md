# Releasing

1. Bump `version` in `build.clj`:
   ```clojure
   (def version "0.1.1")
   ```
2. Commit and push to `main`:
   ```sh
   git commit -am "release 0.1.1"
   git push origin main
   ```
3. Run the release workflow: GitHub → Actions → Release → Run workflow
   (or `gh workflow run release.yml`).

The workflow runs the tests, deploys to Clojars and pushes the tag `v0.1.1`.
Don't create the tag yourself: the workflow fails if `v<version>` already exists.

To check the tag afterwards:
```sh
git fetch --tags && git tag -l 'v*'
```
