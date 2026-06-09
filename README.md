# Infisical Credentials Provider for Jenkins

A Jenkins plugin that exposes secrets stored in [Infisical](https://infisical.com)
as **native Jenkins credentials**. Once installed and configured, Infisical
secrets resolve through the standard `withCredentials` step and any
credential-consuming plugin (Git, Docker, SSH Agent, …) — with no custom
secret-fetch code in your pipelines.

It is a read-only credentials *provider*: the plugin never writes to Infisical,
and the credentials it surfaces cannot be edited from Jenkins. Secret **values**
are fetched lazily (only when a binding actually reads them) and cached briefly;
listing and the `/credentials/` page only ever expose secret **names**.

## Supported credential types

| Jenkins credential | Backed by | Typical use |
|---|---|---|
| Secret text (`StringCredentials`) | the secret value | `withCredentials([string(...)])` |
| Username / password (`StandardUsernamePasswordCredentials`) | value = password, username from metadata | `gitUsernamePassword`, `usernamePassword(...)` |
| SSH private key (`SSHUserPrivateKey`) | value = PEM private key | `sshagent`, `sshUserPrivateKey(...)` |
| Secret file (`FileCredentials`) | value = file content | `withCredentials([file(...)])` |

## Type-inference convention

Each Infisical secret maps to exactly **one** Jenkins credential. The Jenkins
credential *type* is chosen from the secret's `jenkins-type` **metadata** value
(case-insensitive), defaulting to secret text. Non-secret details (username,
passphrase, file name, id, description) also come from metadata, so a single
Infisical secret is enough to define a fully-typed credential.

| `jenkins-type` | Jenkins credential | Extra metadata (defaults) |
|---|---|---|
| *absent* or `secretText` | Secret text | — |
| `usernamePassword` | Username / password | `jenkins-username` (default `x-access-token`) |
| `sshPrivateKey` | SSH private key | `jenkins-username` (default `git`), `jenkins-passphrase` (optional) |
| `file` | Secret file | `jenkins-filename` (default = the secret key) |

Common metadata for every type:

| Metadata key | Effect | Default |
|---|---|---|
| `jenkins-id` | Jenkins credential id (what pipelines reference) | the Infisical secret key |
| `jenkins-description` | credential description | `Infisical: <key>` |

> The `usernamePassword` default username `x-access-token` matches GitHub /
> GitHub Enterprise token-over-HTTPS authentication, so a personal-access-token
> secret tagged `jenkins-type=usernamePassword` works directly with
> `gitUsernamePassword` — no extra metadata needed.

Metadata is read from Infisical's per-secret **secret metadata** (`key`/`value`
pairs). Tags are also exposed (as `tag:<slug>`), but `jenkins-type` should be set
as secret metadata.

## Configuration

### Via the UI

**Manage Jenkins → System → Infisical:**

- **Server URL** — e.g. `https://infisical.example.com` (or `https://app.infisical.com`
  for Infisical Cloud)
- **Project ID or slug** — the Infisical project, as either its UUID
  (sent as `workspaceId`) or its slug such as `my-project` (sent as `workspaceSlug`).
  The plugin auto-detects which based on the value's shape.
- **Environment** — environment slug, e.g. `prod`
- **Secret path** — e.g. `/ci` (defaults to `/`)
- **Auth credential** — a **username/password** credential whose *username* is the
  machine-identity **clientId** and *password* is the **clientSecret**
  (Universal Auth).

Use **Test connection** to perform a real login + secret listing and confirm the
configuration (it reports the number of secrets found, or a specific error).

### Via Configuration as Code (JCasC)

```yaml
unclassified:
  infisical:
    serverUrl: "https://infisical.example.com"
    projectId: "my-project"
    environment: "prod"
    secretPath: "/ci"
    credentialsId: "infisical-machine-identity"
```

The bootstrap username/password credential (`credentialsId`) is resolved from the
**system** credentials store. (This direct system-store lookup is also the
recursion guard: because the plugin itself can supply username/password
credentials, resolving its own bootstrap credential must bypass the provider — it
does, by reading the system store directly.)

## Pipeline usage

Reference a credential by the Infisical secret key (or its `jenkins-id`):

```groovy
// Secret text
withCredentials([string(credentialsId: 'API_TOKEN', variable: 'TOKEN')]) {
    sh 'curl -H "Authorization: Bearer $TOKEN" https://api.example.com/'
}

// Secret file (TLS cert, kubeconfig, …)
withCredentials([file(credentialsId: 'DB_CLIENT_CERT', variable: 'DB_CLIENT_CERT_FILE')]) {
    sh 'psql "sslcert=$DB_CLIENT_CERT_FILE ..." ...'
}

// git over HTTPS (secret tagged jenkins-type=usernamePassword)
gitUsernamePassword(credentialsId: 'GIT_HTTPS_TOKEN') {
    sh 'git fetch ...'
}

// git over SSH (secret tagged jenkins-type=sshPrivateKey)
sshagent(['GIT_DEPLOY_KEY']) {
    sh 'git push --mirror git@github.com:example/repo.git'
}
```

Bound values are masked in build logs, exactly as for any Jenkins credential.

## Building

Requires JDK 17 or 21 (the project builds with JDK 21) and Maven 3.9+.

```sh
mvn -U clean verify      # compile, test, SpotBugs, and produce the .hpi
mvn hpi:run              # dev Jenkins at http://localhost:8080/jenkins/ for manual smoke tests
```

The build produces `target/infisical-credentials-provider.hpi`.

## Deployment notes

A credentials provider registers its extension points **at boot**, so installing
or upgrading this plugin **requires a Jenkins restart** (no hot-swap). A typical
automated rollout builds a pinned tag with `mvn -U clean verify`, installs the
resulting `.hpi` (copy into `$JENKINS_HOME/plugins/` or use the REST
`uploadPlugin` endpoint), and performs a safe restart. Pin the installed version
in your Configuration as Code so rebuilds are reproducible.

## Compatibility

- Jenkins: `2.541.3`+ (built against the `2.541.x` plugin BOM).
- Java: 17 or 21.
