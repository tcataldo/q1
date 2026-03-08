# q1-ansible

Ansible pour déployer un cluster etcd 3 nœuds + un cluster q1 sur les machines `q1-01`, `q1-02`, `q1-03`.

## Structure

```
q1-ansible/
  ansible.cfg          # config SSH (IdentityAgent GCR, pas de host_key_checking)
  inventory.ini        # q1-01/02/03 dans les groupes [etcd] et [q1]
  group_vars/all.yml   # variables communes (user=root, ports, RF=3, endpoints etcd)
  roles/
    java/              # installe Temurin 25 JDK via Adoptium APT (noble)
    etcd/              # installe etcd 3.5.17, configure cluster 3 nœuds, systemd
    q1/                # build JAR localement, déploie, configure, systemd
  site.yml             # déploiement complet (java → etcd → q1)
  deploy-q1.yml        # redéploiement q1 seul
```

## Rôle java

Ajoute le dépôt Adoptium et installe `temurin-25-jdk`. Nécessaire pour faire tourner le JAR q1
avec `--enable-preview` et `--enable-native-access=ALL-UNNAMED`.

## Rôle etcd

- Télécharge le binaire etcd depuis GitHub releases (version définie dans `defaults/main.yml`)
- Crée l'utilisateur système `etcd` et le répertoire de données `/var/lib/etcd`
- Peuple `/etc/hosts` avec les IPs des 3 nœuds (résolution DNS entre VMs)
- Génère `/etc/etcd/etcd.env` depuis le template Jinja2 (INITIAL_CLUSTER calculé depuis l'inventaire)
- Détecte automatiquement si le cluster est déjà initialisé (`/var/lib/etcd/member`) pour basculer
  `ETCD_INITIAL_CLUSTER_STATE` entre `new` et `existing`
- Démarre etcd en async sur tous les nœuds simultanément (`strategy: free` dans site.yml),
  puis vérifie le port 2379 avec `wait_for`

## Rôle q1

- Build le JAR localement via `mvn package -DskipTests` (`delegate_to: localhost, run_once: true`)
- Crée l'utilisateur système `q1` et les répertoires `/opt/q1`, `/var/lib/q1/data`, `/var/log/q1`
- Copie le JAR vers `/opt/q1/q1-api.jar`
- Génère `/etc/q1/q1.env` (Q1_NODE_ID, Q1_HOST, Q1_PORT, Q1_DATA_DIR, Q1_ETCD, Q1_RF)
- Service systemd avec restart automatique, logs dans `/var/log/q1/q1.log`

## Quirks connus

- **SSH** : la clé est déployée sur `root`. L'agent SSH tourne via GCR GNOME
  (`/run/user/1000/gcr/ssh`) — `IdentityAgent` est passé explicitement dans `ansible.cfg`.
- **etcd bootstrap** : le play etcd utilise `strategy: free` pour démarrer les 3 nœuds en
  parallèle. Sans ça, le premier nœud ne peut pas former le quorum et systemd timeout.
- **mvn** : installé dans `/home/tom/java/maven/bin/mvn`, pas dans `/usr/bin`. Le task shell
  hérite du `PATH` de la session locale via `lookup('env', 'PATH')`.
