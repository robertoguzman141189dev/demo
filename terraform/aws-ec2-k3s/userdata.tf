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
  EOT
}
