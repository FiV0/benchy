# Benchy

Benchy is a small library that in Clojure spirit tries to write benchmarks as data on functions.
The idea is to create larger benchmarks from a set of small primitives. It tries to provide
orchestration, tracing, timing and structured reports for benchmarks.

The library was originally extracted from [XTDB](https://github.com/xtdb/xtdb/) where I first encountered it.

## Example

## Worker map

## Tasks

## Development

```sh
clj -X:test
clj -T:build jar
```

The build creates a regular library JAR and Maven metadata under `target/`; dependencies are not bundled.
No CLI or concrete benchmark suite is included.

### Licence

Benchy is licensed under Mozilla Public License 2.0.

See [LICENSE](LICENSE) for the full licence.
