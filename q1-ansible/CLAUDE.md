# q1-ansible

Ansible playbooks to deploy a q1 cluster on hosts `q1-01`, `q1-02`, `q1-03`.
No external coordinator — Apache Ratis (embedded Raft) handles consensus.

## Structure

```
q1-ansible/
  ansible.cfg          # SSH config (IdentityAgent GCR, no host_key_checking)
  inventory.ini        # q1-01/02/03 in [q1] group
  group_vars/all.yml   # shared variables (user=root, ports, peer list)
  roles/
    java/              # installs Temurin 25 JDK via Adoptium APT (noble)
    q1/                # builds JAR locally, deploys, configures, systemd
  site.yml             # full deployment (java → q1)
  deploy-q1.yml        # q1-only redeployment
```

## java role

Adds the Adoptium repository and installs `temurin-25-jdk`. Required to run the q1 JAR
with `--enable-preview` and `--enable-native-access=ALL-UNNAMED`.

## q1 role

- Builds the JAR locally via `mvn package -DskipTests` (`delegate_to: localhost, run_once: true`)
- Creates the `q1` system user and directories `/opt/q1`, `/var/lib/q1/data`, `/var/log/q1`
- Copies the JAR to `/opt/q1/q1-api.jar`
- Generates `/etc/q1/q1.env` (Q1_NODE_ID, Q1_HOST, Q1_PORT, Q1_RAFT_PORT, Q1_DATA_DIR, Q1_PEERS)
- Systemd service with automatic restart, logs in `/var/log/q1/q1.log`

`Q1_PEERS` is built dynamically from the inventory:
`id|host|httpPort|raftPort` per node, comma-separated.
Example: `q1-01|10.0.0.1|9000|6000,q1-02|10.0.0.2|9000|6000,q1-03|10.0.0.3|9000|6000`

## Known quirks

- **SSH**: the key is deployed on `root`. The SSH agent runs via GCR GNOME
  (`/run/user/1000/gcr/ssh`) — `IdentityAgent` is passed explicitly in `ansible.cfg`.
- **mvn**: installed at `/home/tom/java/maven/bin/mvn`, not in `/usr/bin`. The shell task
  inherits the local session `PATH` via `lookup('env', 'PATH')`.
- **Raft bootstrap**: all 3 nodes must start before quorum is achieved (⌊3/2⌋+1 = 2).
  The systemd service restarts automatically until the cluster reaches quorum.
