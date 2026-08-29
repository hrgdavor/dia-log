# Publishing to Maven Central

This project publishes `dia-log-core` and `dia-log-logback` to Maven Central via the
[Central Portal](https://central.sonatype.com) using the
[`central-publishing-maven-plugin`](https://central.sonatype.org/publish/publish-portal-maven/).
The `dia-log-example` module is **not** published.

## Prerequisites

1. **A Maven Central account** — register at <https://central.sonatype.com>.
2. **Namespace verification** — verify ownership of the `hr.hrg.dialog` group.
   This is done once in the Central Portal UI. If the namespace is not yet
   available, request it via "Add Namespace".
3. **A GPG key** — generate one and publish its public key to a keyserver
   (Central Portal validates signatures against public keyservers).

   ```bash
   gpg --full-generate-key
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --armor --export <KEY_ID>
   ```

4. **Central Portal credentials** in `~/.m2/settings.xml`. Generate a token in
   the Central Portal UI (Account → User Token) and add it as a server entry:

    ```xml
    <settings>
      <servers>
        <server>
          <id>central</id>
          <username>YOUR_TOKEN_USERNAME</username>
          <password>YOUR_TOKEN_PASSWORD</password>
        </server>
        <!-- maven-gpg-plugin reads the signing passphrase from here
             (configured via <passphraseServerId>gpg</passphraseServerId>). -->
        <server>
          <id>gpg</id>
          <passphrase>YOUR_GPG_PASSPHRASE</passphrase>
        </server>
      </servers>
      <profiles>
        <profile>
          <id>central</id>
          <activation><activeByDefault>true</activeByDefault></activation>
          <properties>
            <gpg.executable>gpg</gpg.executable>
            <gpg.keyname>YOUR_GPG_KEY_ID</gpg.keyname>
          </properties>
        </profile>
      </profiles>
    </settings>
    ```

    > **Security note:** never commit `settings.xml` or tokens to the repository.
    > A `.github/workflows` release uses GitHub secrets instead (see below).

### Windows: avoid the GPG passphrase prompt stall

On Windows the default `pinentry` pops a GUI dialog that does not render in a
non-interactive console, so signing hangs. The build already passes
`--pinentry-mode loopback`, but GPG only honors loopback when the agent allows
it. Enable it once in your GnuPG home (`%APPDATA%\gnupg\gpg-agent.conf`):

```
allow-loopback-pinentry
```

Then restart the agent:

```powershell
gpgconf --kill gpg-agent
```

With the `gpg` server entry above supplying the passphrase, signing proceeds
without any prompt.

## One-time release (local)

The `maven-gpg-plugin` runs at the `verify` phase and the
`central-publishing-maven-plugin` runs at `deploy`. To publish a release:

```bash
export JAVA_HOME=<path-to-jdk-25>
mvn clean deploy -pl core,logback -am -DskipTests -Dgpg.skip=false
```

> **Use the `-pl core,logback -am` form, not a bare `mvn clean deploy`.**
> This plugin creates the deployment only on the *last* module in the reactor
> that has the publishing mojo, and it honors that module's `skipPublishing`
> flag. `example` and `project-automation` set `skipPublishing=true` and would
> otherwise be the last modules in the reactor, so a bare `mvn clean deploy`
> builds the bundle but then prints "Skipping Central Publishing at user's
> request." and never uploads it. Targeting `core,logback` with `-am` (also
> make) builds and publishes the parent POM plus `core` and `logback`, and
> leaves `example`/`project-automation` out of the deploy reactor entirely.
>
> Also note: the `mvn` on your PATH is an **mvnd** shim that silently skips the
> publishing mojo. Invoke the real Maven binary directly, e.g.
> `D:\programs\mvn\bin\mvn.cmd` on Windows.

> `gpg.skip` defaults to `true` so local dev builds don't stall on signing;
> pass `-Dgpg.skip=false` only for a real release.



The plugin bundles the artifacts, signs them, deploys to Central Portal, and
auto-publishes them (because `autoPublish` is `true`).

If you prefer to review before publishing, set `-DautoPublish=false` and
trigger publishing from the Central Portal UI.

## Automated releases (GitHub Actions)

A `.github/workflows/publish.yml` workflow is included. It runs on a manually
dispatched release and requires the following repository secrets:

| Secret | Description |
|--------|-------------|
| `CENTRAL_USERNAME` | Central Portal token username |
| `CENTRAL_PASSWORD` | Central Portal token password |
| `GPG_PRIVATE_KEY` | ASCII-armored private GPG key |
| `GPG_PASSPHRASE` | GPG key passphrase |

The workflow:
1. Checks out the code and sets up JDK 25.
2. Imports the GPG key.
3. Runs `mvn deploy` with the token and signing credentials wired through
   Maven settings.

## Versioning

Bump the `<version>` in the parent `pom.xml` before a release. All modules
inherit the parent version, so only the parent needs updating. Update the
`<scm><tag>` to match the release tag (e.g. `v1.0.1`).

## Verification checklist

Before publishing, confirm:

- [ ] `mvn clean package` builds successfully (sources + javadoc jars generated).
- [ ] POMs declare `<licenses>`, `<developers>`, and `<scm>`.
- [ ] GPG public key is published to a keyserver.
- [ ] Central Portal namespace `hr.hrg.dialog` is verified.
- [ ] `settings.xml` has the `central` server credentials.
- [ ] Version is a release version (no `-SNAPSHOT` suffix).
