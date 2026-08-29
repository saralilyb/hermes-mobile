# LaTeX 1.5.3 dependency qualification

Qualification date: 2026-08-29

## Decision

Hermes Mobile accepts `io.github.huarangmeng` LaTeX 1.5.3 with strict
artifact checksums and source/license provenance. The evidence supports using
the dependency, but it does not establish an independently authenticated
publisher identity.

## Source provenance

The published Maven metadata names `huarangmeng/latex` as its project and
source-control repository (high, [13]). The repository's
`release-1.5.3` lightweight tag resolves to commit
`4355bf11d7224c28c3f2105d937f200b14a03b4e` (high, [3]). The tag is not an
annotated or signed Git tag.

The dependency's source is licensed under the MIT License. Its renderer bundles
20 KaTeX 0.16.11 TrueType fonts, also under the MIT License (high, [6], [14]).
The required notices are carried in the repository-level `NOTICE` file, which
is included in every GitHub release.

## Artifact checks

The audit checked all nine resolved `io.github.huarangmeng` artifacts recorded
in `gradle/verification-metadata.xml`:

- each local artifact's SHA-256 matched the pinned Gradle metadata;
- each local artifact matched an independently fetched Maven Central checksum
  sidecar;
- all 20 fonts in the resolved `latex-renderer-android` AAR matched the files
  at source tag `release-1.5.3` byte for byte.

The audit parsed `gradle/verification-metadata.xml`, hashed the resolved Gradle
cache artifacts, checked the repository sidecars, and compared font entries in
the renderer AAR with the source tag. It returned nine verified artifacts,
20 matching source fonts, and no failures. The pinned checksums remain the
enforceable build input.

## Signature limitation

Maven Central publishes a detached EdDSA signature whose issuer key ID is
`B5DD1925313DD6D8`. An independently retrieved OpenPGP key has fingerprint
`D1960254A20EFDA7FC96F62BB5DD1925313DD6D8`, but it contains no certified user
ID and the source tag is unsigned. The signature therefore is not treated as
proof that a separately authenticated publisher identity produced the Maven
artifacts.

The accepted assurance is narrower: source repository and Maven metadata
agreement, an exact source tag, byte-matching bundled resources, Maven checksum
sidecars, and strict Gradle verification. A future release with a signed tag or
an independently identity-bound key should replace this limitation rather than
being assumed equivalent.

## Sources

[3] https://github.com/huarangmeng/latex/tree/release-1.5.3
[6] https://github.com/huarangmeng/latex/blob/release-1.5.3/THIRD_PARTY_NOTICES.md
[13] https://repo.maven.apache.org/maven2/io/github/huarangmeng/latex-renderer/1.5.3/latex-renderer-1.5.3.pom
[14] https://github.com/KaTeX/KaTeX/blob/v0.16.11/LICENSE
