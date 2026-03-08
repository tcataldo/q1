# q1-ansible

Déploiement du cluster etcd + cluster q1 sur `q1-01`, `q1-02`, `q1-03`.

## Prérequis

- Ansible installé en local (`ansible --version`)
- Clé SSH déployée sur `root@q1-01/02/03`
- Maven accessible dans le PATH local (pour builder le JAR)

## Déploiement complet

Premier déploiement ou mise à jour complète (Java + etcd + q1) :

```bash
cd q1-ansible
ansible-playbook site.yml
```

## Redéployer q1 uniquement

Après un changement de code, rebuild + redéploiement des 3 nœuds q1 :

```bash
ansible-playbook deploy-q1.yml
```

Sans rebuilder le JAR (si déjà buildé) :

```bash
ansible-playbook deploy-q1.yml -e q1_build=false
```

Sur un seul nœud :

```bash
ansible-playbook deploy-q1.yml -l q1-01
```

## Vérifications

Tester la connectivité Ansible :

```bash
ansible cluster -m ping
```

État du cluster etcd :

```bash
ssh root@q1-01 'ETCDCTL_API=3 etcdctl \
  --endpoints=http://q1-01:2379,http://q1-02:2379,http://q1-03:2379 \
  endpoint status --write-out=table'
```

État des services q1 :

```bash
ansible q1 -m command -a "systemctl status q1 --no-pager" -b
```

Logs q1 sur un nœud :

```bash
ssh root@q1-01 'tail -f /var/log/q1/q1.log'
```

Tester l'API S3 :

```bash
curl http://q1-01:9000/
```

## Redémarrer les services

```bash
# etcd
ansible etcd -m systemd -a "name=etcd state=restarted" -b

# q1
ansible q1 -m systemd -a "name=q1 state=restarted" -b
```

## Variables principales (`group_vars/all.yml`)

| Variable          | Défaut                           | Description                        |
|-------------------|----------------------------------|------------------------------------|
| `ansible_user`    | `root`                           | Utilisateur SSH                    |
| `q1_version`      | `0.1.0-SNAPSHOT`                 | Version du JAR à déployer          |
| `q1_port`         | `9000`                           | Port HTTP q1                       |
| `q1_rf`           | `3`                              | Facteur de réplication             |
| `q1_build`        | `true`                           | Builder le JAR avant déploiement   |
| `etcd_version`    | `3.5.17`                         | Version etcd                       |
