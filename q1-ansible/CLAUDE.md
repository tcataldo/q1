# q1-ansible

Ansible playbooks to deploy a 3-node etcd cluster + a q1 cluster on hosts `q1-01`, `q1-02`, `q1-03`.

## Structure

```
q1-ansible/
  ansible.cfg          # SSH config (IdentityAgent GCR, no host_key_checking)
  inventory.ini        # q1-01/02/03 in [etcd] and [q1] groups
  group_vars/all.yml   # shared variables (user=root, ports, RF=3, etcd endpoints)
  roles/
    java/              # installs Temurin 25 JDK via Adoptium APT (noble)
    etcd/              # installs etcd 3.5.17, configures 3-node cluster, systemd
    q1/                # builds JAR locally, deploys, configures, systemd
  site.yml             # full deployment (java → etcd → q1)
  deploy-q1.yml        # q1-only redeployment
```

## java role

Adds the Adoptium repository and installs `temurin-25-jdk`. Required to run the q1 JAR
with `--enable-preview` and `--enable-native-access=ALL-UNNAMED`.

## etcd role

- Downloads the etcd binary from GitHub releases (version defined in `defaults/main.yml`)
- Creates the `etcd` system user and `/var/lib/etcd` data directory
- Populates `/etc/hosts` with the 3 node IPs (DNS resolution between VMs)
- Generates `/etc/etcd/etcd.env` from the Jinja2 template (`INITIAL_CLUSTER` computed from inventory)
- Automatically detects if the cluster is already initialized (`/var/lib/etcd/member`) to switch
  `ETCD_INITIAL_CLUSTER_STATE` between `new` and `existing`
- Starts etcd asynchronously on all nodes simultaneously (`strategy: free` in site.yml),
  then checks port 2379 with `wait_for`

## q1 role

- Builds the JAR locally via `mvn package -DskipTests` (`delegate_to: localhost, run_once: true`)
- Creates the `q1` system user and directories `/opt/q1`, `/var/lib/q1/data`, `/var/log/q1`
- Copies the JAR to `/opt/q1/q1-api.jar`
- Generates `/etc/q1/q1.env` (Q1_NODE_ID, Q1_HOST, Q1_PORT, Q1_DATA_DIR, Q1_ETCD, Q1_RF)
- Systemd service with automatic restart, logs in `/var/log/q1/q1.log`

## Known quirks

- **SSH**: the key is deployed on `root`. The SSH agent runs via GCR GNOME
  (`/run/user/1000/gcr/ssh`) — `IdentityAgent` is passed explicitly in `ansible.cfg`.
- **etcd bootstrap**: the etcd play uses `strategy: free` to start all 3 nodes in parallel.
  Without this, the first node cannot form quorum and systemd times out.
- **mvn**: installed at `/home/tom/java/maven/bin/mvn`, not in `/usr/bin`. The shell task
  inherits the local session `PATH` via `lookup('env', 'PATH')`.
