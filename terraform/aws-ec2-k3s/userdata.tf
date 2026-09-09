# Arranque del nodo.
#
# Se queda en un local y no en un archivo aparte porque son veinte líneas y
# tenerlo aquí evita el salto entre ficheros al leerlo. Si crece, se separa.

locals {
  user_data = <<-EOT
    #!/bin/bash
    set -euxo pipefail

    # -e para que un fallo detenga el arranque en vez de dejar un nodo a medio
    # instalar que parece sano. Los logs acaban en /var/log/cloud-init-output.log,
    # que es el primer sitio donde mirar si el nodo arranca y k3s no está.

    dnf install -y --setopt=install_weak_deps=False tar

    # Versión fijada y Traefik desactivado.
    #
    # k3s trae Traefik como controlador de Ingress por defecto. Este repositorio
    # usa ingress-nginx: es lo que se probó en kind y lo que llevará el rate
    # limiting de F7, y tener dos controladores compitiendo por el puerto 80
    # produce un fallo confuso. Se desactiva aquí y el controlador correcto lo
    # instala Argo CD.
    #
    # servicelb SÍ se conserva: es lo que permite que un Service de tipo
    # LoadBalancer se ate a los puertos 80 y 443 del nodo sin un balanceador de
    # AWS, que costaría más que la propia instancia.
    curl -sfL https://get.k3s.io \
      | INSTALL_K3S_VERSION="${var.k3s_version}" \
        INSTALL_K3S_EXEC="server --disable traefik --write-kubeconfig-mode 0640" \
        sh -

    systemctl is-active --quiet k3s

    # Deja el kubeconfig legible por el grupo, para poder usarlo desde una sesión
    # de SSM sin ser root. No se copia a ningún sitio ni se sube: quien entra por
    # Session Manager ya está autenticado contra AWS.
    install -d -m 0750 /etc/rancher/k3s

    # ------------------------------------------------ credenciales de ECR -----
    #
    # EKS resuelve esto solo: su kubelet trae un proveedor de credenciales que
    # cambia el rol del nodo por un token de ECR sin que nadie lo pida. k3s no lo
    # trae, y el sintoma es un ErrImagePull con "no basic auth credentials" que no
    # menciona IAM por ningun lado.
    #
    # La solucion aqui es deliberadamente aburrida: un temporizador que cambia el
    # rol de la instancia por un token y lo deja como Secret del namespace. No hay
    # ninguna credencial permanente: el rol lo da AWS a la instancia y el token
    # dura doce horas, asi que se renueva cada seis.
    cat > /usr/local/bin/refrescar-ecr.sh <<'SCRIPT'
    #!/bin/sh
    set -eu
    REGION=us-east-1
    CUENTA=$(aws sts get-caller-identity --query Account --output text --region $REGION)
    REGISTRO=$CUENTA.dkr.ecr.$REGION.amazonaws.com
    TOKEN=$(aws ecr get-login-password --region $REGION)

    /usr/local/bin/k3s kubectl create namespace job-forge \
      --dry-run=client -o yaml | /usr/local/bin/k3s kubectl apply -f -

    /usr/local/bin/k3s kubectl -n job-forge create secret docker-registry ecr-pull \
      --docker-server=$REGISTRO --docker-username=AWS --docker-password="$TOKEN" \
      --dry-run=client -o yaml | /usr/local/bin/k3s kubectl apply -f -
    SCRIPT
    sed -i 's/^    //' /usr/local/bin/refrescar-ecr.sh
    chmod 0755 /usr/local/bin/refrescar-ecr.sh

    cat > /etc/systemd/system/refrescar-ecr.service <<'UNIT'
    [Unit]
    Description=Renueva el token de acceso a ECR como Secret de Kubernetes
    After=k3s.service
    Requires=k3s.service

    [Service]
    Type=oneshot
    ExecStart=/usr/local/bin/refrescar-ecr.sh
    UNIT
    sed -i 's/^    //' /etc/systemd/system/refrescar-ecr.service

    cat > /etc/systemd/system/refrescar-ecr.timer <<'TIMER'
    [Unit]
    Description=Renueva el token de ECR cada seis horas

    [Timer]
    OnBootSec=90s
    OnUnitActiveSec=6h
    Persistent=true

    [Install]
    WantedBy=timers.target
    TIMER
    sed -i 's/^    //' /etc/systemd/system/refrescar-ecr.timer

    systemctl daemon-reload
    systemctl enable --now refrescar-ecr.timer
  EOT
}
