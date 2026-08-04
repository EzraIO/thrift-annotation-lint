# Releasing to Maven Central

Maven Central releases are immutable. Confirm the version, changelog, tests,
and Git tag before uploading a release.

## One-time setup

1. Sign in to the [Central Portal](https://central.sonatype.com/) with the
   `EzraIO` GitHub account and confirm that `io.github.ezraio` is verified.
2. Generate a Central Portal user token.
3. Create a GPG signing key and publish its public key to a public key server.
4. Add these GitHub Actions repository secrets:
   `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, and
   `GPG_PASSPHRASE`.

Do not upload until the Central Portal shows `io.github.ezraio` as verified.

## Publish

1. Run `mvn clean verify` locally and ensure CI is green.
2. Update the release notes and remove stale `Unreleased` claims.
3. Run the **Publish to Maven Central** workflow manually.
4. Inspect the validated deployment in the Central Portal. The workflow does
   not auto-publish.
5. Click **Publish** only after checking the coordinates and artifacts.
6. Tag the exact published commit and create the matching GitHub Release.

If the Portal UI is unavailable, copy the validated deployment UUID from the
upload workflow log and run **Publish validated Maven Central deployment**.
That workflow verifies the deployment UUID, `VALIDATED` state, and exact
`io.github.ezraio:thrift-annotation-lint:0.2.3` component before calling the
Central Publisher API and waiting for `PUBLISHED`.

For local publishing, use Maven `3.6.3` or newer, configure a Maven server named `central` in
`~/.m2/settings.xml`, import the GPG private key, and run:

```bash
mvn --batch-mode --no-transfer-progress -Prelease clean deploy
```

The release profile attaches source and Javadoc JARs, signs every artifact, and
uploads the bundle to the Central Portal for validation.
