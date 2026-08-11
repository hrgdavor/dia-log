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

## One-time release (local)

The `maven-gpg-plugin` runs at the `verify` phase and the
`central-publishing-maven-plugin` runs at `deploy`. To publish a release:

```bash
export JAVA_HOME=<path-to-jdk-25>
mvn clean deploy -DskipTests
```

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
