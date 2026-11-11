# Releasing

Tag the commit on `main` you want to release and push the tag:

```sh
git tag v0.1.1
git push origin v0.1.1
```

The release workflow takes the version from the tag (`v0.1.1` → `0.1.1`),
runs the tests and deploys to Clojars. No version bump commit is needed.

Local builds without a tag use `0.1.0-SNAPSHOT` (see `build.clj`).
