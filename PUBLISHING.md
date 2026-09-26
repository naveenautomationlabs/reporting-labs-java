# Publishing to Maven Central

reportingLabs Java publishes to Maven Central via the **Central Portal**
(the sonatype.central successor to OSSRH). This file is the runbook — do
these steps once, then a signed release is one `mvn` command.

## One-time setup (human only)

You need three things: a Central account, a GPG key, and Maven credentials.

### 1. Sonatype Central account + namespace verification

1. Sign up at https://central.sonatype.com (use your GitHub identity).
2. Register the `io.github.naveenanimation20` namespace under **Namespaces →
   Add namespace**. Central verifies ownership by asking you to publish a
   TXT record in the DNS of the namespace *or* to create a public
   GitHub repo whose name matches a token they give you. GitHub option
   is easiest — takes 2 minutes.
3. Wait until the namespace shows **Verified**.

> Why `io.github.naveenanimation20` and not `dev.reportinglabs`?
> Central only verifies namespaces you own. `dev.reportinglabs`
> would need someone to own a `github.com/reportinglabs` account; you
> own `github.com/naveenanimation20`, so that's the safe namespace.
> Update every `<groupId>` in the four `pom.xml` files to match once
> the namespace is verified — search-replace `dev.reportinglabs`
> → `io.github.naveenanimation20`.

### 2. GPG key

Central rejects any artifact that is not signed with a GPG key whose
public half is on a keyserver.

```bash
# Generate a key (choose 'RSA and RSA', 4096 bits, no expiry, your email).
gpg --full-generate-key

# List keys and copy the long fingerprint after "sec".
gpg --list-secret-keys --keyid-format=long

# Publish the public key. Central polls all of these.
KEY=<your-key-id>
gpg --keyserver keys.openpgp.org       --send-keys $KEY
gpg --keyserver keyserver.ubuntu.com   --send-keys $KEY
gpg --keyserver keys.openpgp.org --recv-keys $KEY   # sanity check
```

Note the key's passphrase — you'll need it for CI publish.

### 3. Maven credentials

Add a `<server>` block to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_CENTRAL_TOKEN_USERNAME</username>
      <password>YOUR_CENTRAL_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

Get the token from https://central.sonatype.com → **Account → Generate
User Token** (they call it "user token"; it's a username/password pair).

## Publishing a release

Once the one-time setup is done, every release looks like this:

```bash
# 1. Set the release version (drops the -SNAPSHOT).
mvn -B versions:set -DnewVersion=0.1.0 -DprocessAllModules

# 2. Build, sign, upload to Central. The `release` profile turns on
#    source jars, javadoc jars, GPG signing, and the Central publisher.
mvn -B -Prelease clean deploy \
  -Dgpg.keyname=YOUR_KEY_ID \
  -Dgpg.passphrase=YOUR_KEY_PASSPHRASE

# 3. Commit + tag the release.
git commit -am "release 0.1.0"
git tag v0.1.0
git push origin main --tags

# 4. Set the next dev version.
mvn -B versions:set -DnewVersion=0.1.1-SNAPSHOT -DprocessAllModules
git commit -am "prepare next dev cycle"
git push origin main
```

The `autoPublish=false` setting in the parent POM means the release lands
on Central in a **staging** state — you must click **Publish** at
https://central.sonatype.com/publishing/deployments after verifying the
staged bundle looks right. To auto-publish (no manual click), flip
`<autoPublish>true</autoPublish>` in the parent POM's `release` profile.

## Publishing from CI

The `.github/workflows/release.yml` workflow triggers on tag push
(`v*`). It reuses the same `mvn -Prelease deploy` command with GPG and
Central credentials from repo secrets. To turn it on:

1. Repository → **Settings → Secrets and variables → Actions**.
2. Add these secrets:
   - `CENTRAL_USERNAME` — user token username from Central
   - `CENTRAL_PASSWORD` — user token password
   - `GPG_PRIVATE_KEY`  — output of `gpg --armor --export-secret-keys $KEY`
   - `GPG_PASSPHRASE`   — the key's passphrase

After that, `git tag v0.1.1 && git push --tags` publishes automatically.

## Troubleshooting

- **`No signature file`** → you set `-Prelease` but not `-Dgpg.keyname`.
  Pass it in or add `<gpg.keyname>...</gpg.keyname>` to your
  `~/.m2/settings.xml`.
- **`401 Unauthorized`** from Central → the `<id>` on your `<server>`
  block must match `<publishingServerId>` in the parent POM (`central`).
- **Namespace unverified** → Central will accept the upload but reject
  publish. Finish step 2 above first.
- **Public key not visible** → after `--send-keys`, wait 30 minutes for
  Central's keyserver poll to pick it up. Re-run the send if unclear.

## Reference

- Central Portal docs: https://central.sonatype.org/publish/publish-portal-maven/
- `central-publishing-maven-plugin`: https://github.com/sonatype/central-publishing-maven-plugin
- Sign requirements: https://central.sonatype.org/publish/requirements/gpg/
