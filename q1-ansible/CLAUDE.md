# q1-ansible

Ansible playbooks to deploy a q1 cluster on hosts `q1-01`, `q1-02`, `q1-03`.
No external coordinator — Apache Ratis (embedded Raft) handles consensus.

## Structure

```
q1-ansible/
  ansible.cfg           # SSH config (IdentityAgent GCR, no host_key_checking)
  inventory.ini         # q1-01/02/03 in [q1] group
  group_vars/all.yml    # shared variables (user=root, ports, peer list, EC config)
  roles/
    java/               # installs Temurin 25 JDK via Adoptium APT (noble)
    q1/                 # builds JAR locally, deploys, configures, systemd
  site.yml              # full deployment (java → q1)
  deploy-q1.yml         # q1-only redeployment (no Java reinstall)
  purge-and-deploy.yml  # stop q1, wipe /var/lib/q1/data, redeploy fresh
```

## java role

Adds the Adoptium repository and installs `temurin-25-jdk`. Required to run the q1 JAR
with `--enable-preview` and `--enable-native-access=ALL-UNNAMED`.

## q1 role

- Builds the JAR locally via `mvn package -DskipTests` (`delegate_to: localhost, run_once: true`)
- Creates the `q1` system user and directories `/opt/q1`, `/var/lib/q1/data`, `/var/log/q1`
- Copies the JAR to `/opt/q1/q1-api.jar`
- Generates `/etc/q1/q1.env` with all env vars (including `Q1_EC_K`, `Q1_EC_M` if set)
- Systemd service with automatic restart, logs in `/var/log/q1/q1.log`

`Q1_PEERS` is built dynamically from the inventory:
`id|host|httpPort|raftPort` per node, comma-separated.

## Key variables (`group_vars/all.yml`)

| Variable | Default | Description |
|---|---|---|
| `ansible_user` | `root` | SSH user |
| `q1_version` | `0.1.0-SNAPSHOT` | JAR version |
| `q1_port` | `9000` | HTTP port |
| `q1_raft_port` | `6000` | Raft gRPC port |
| `q1_build` | `true` | Build JAR before deploy |
| `q1_ec_k` | `0` | EC data shards (0 = plain Raft replication) |
| `q1_ec_m` | `1` | EC parity shards (unused when `q1_ec_k=0`) |

## Known quirks

- **SSH**: key deployed on `root`. SSH agent via GCR GNOME (`/run/user/1000/gcr/ssh`) —
  `IdentityAgent` passed explicitly in `ansible.cfg`.
- **mvn**: at `/home/tom/java/maven/bin/mvn`, not in `/usr/bin`. Shell task inherits
  local session `PATH` via `lookup('env', 'PATH')`.
- **Raft bootstrap**: all 3 nodes must start before quorum is achieved (⌊3/2⌋+1 = 2).
  Systemd service restarts automatically until quorum is reached.
- **Data purge**: use `purge-and-deploy.yml` when switching modes (e.g. EC → replication)
  since data formats are incompatible across modes.
