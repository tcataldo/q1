# q1-ansible

Deploy the etcd cluster + q1 cluster on `q1-01`, `q1-02`, `q1-03`.

## Prerequisites

- Ansible installed locally (`ansible --version`)
- SSH key deployed on `root@q1-01/02/03`
- Maven accessible in the local PATH (to build the JAR)

## Full deployment

First deployment or full update (Java + etcd + q1):

```bash
cd q1-ansible
ansible-playbook site.yml
```

## Redeploy q1 only

After a code change, rebuild + redeploy all 3 q1 nodes:

```bash
ansible-playbook deploy-q1.yml
```

Without rebuilding the JAR (if already built):

```bash
ansible-playbook deploy-q1.yml -e q1_build=false
```

On a single node:

```bash
ansible-playbook deploy-q1.yml -l q1-01
```

## Verification

Test Ansible connectivity:

```bash
ansible cluster -m ping
```

etcd cluster status:

```bash
ssh root@q1-01 'ETCDCTL_API=3 etcdctl \
  --endpoints=http://q1-01:2379,http://q1-02:2379,http://q1-03:2379 \
  endpoint status --write-out=table'
```

q1 service status:

```bash
ansible q1 -m command -a "systemctl status q1 --no-pager" -b
```

q1 logs on a node:

```bash
ssh root@q1-01 'tail -f /var/log/q1/q1.log'
```

Test the S3 API:

```bash
curl http://q1-01:9000/
```

## Restart services

```bash
# etcd
ansible etcd -m systemd -a "name=etcd state=restarted" -b

# q1
ansible q1 -m systemd -a "name=q1 state=restarted" -b
```

## Key variables (`group_vars/all.yml`)

| Variable          | Default                          | Description                        |
|-------------------|----------------------------------|------------------------------------|
| `ansible_user`    | `root`                           | SSH user                           |
| `q1_version`      | `0.1.0-SNAPSHOT`                 | JAR version to deploy              |
| `q1_port`         | `9000`                           | q1 HTTP port                       |
| `q1_rf`           | `3`                              | Replication factor                 |
| `q1_build`        | `true`                           | Build JAR before deployment        |
| `etcd_version`    | `3.5.17`                         | etcd version                       |
