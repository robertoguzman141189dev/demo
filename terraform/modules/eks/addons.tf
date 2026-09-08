# Complementos del cluster.
#
# El driver de EBS es además el primer ejemplo de IRSA de todo el repositorio, y
# el más fácil de leer: un pod del sistema que necesita crear volúmenes en AWS y
# lo hace sin llevar encima ninguna credencial.

locals {
  # El emisor sin el "https://" delante. IAM lo quiere así en las condiciones de
  # confianza, y es una fuente de errores clásica: con el esquema, la condición
  # nunca casa y el pod recibe un AccessDenied que no explica por qué.
  oidc_issuer_host = replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")
}

# ---------------------------------------------------- IRSA del driver EBS ---

data "aws_iam_policy_document" "ebs_csi_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.this.arn]
    }

    # Estas dos condiciones son la diferencia entre "este ServiceAccount concreto"
    # y "cualquier pod del cluster". Sin la de :sub, cualquier carga del cluster
    # podría asumir el rol; es el error que convierte IRSA en una puerta abierta.
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_issuer_host}:sub"
      values   = ["system:serviceaccount:kube-system:ebs-csi-controller-sa"]
    }

    # Y esta impide que el token se presente con otra audiencia.
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_issuer_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ebs_csi" {
  name               = "${var.name}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.ebs_csi_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

# ------------------------------------------------------------ complementos --

# Sin resolver_conflicts_on_create, un complemento que ya venga preinstalado en
# el cluster hace fallar el apply en vez de adoptarse.
resource "aws_eks_addon" "core" {
  for_each = toset(["coredns", "kube-proxy", "vpc-cni"])

  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = each.value
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = var.tags

  # coredns necesita nodos donde colocarse: sin esperar al grupo, el complemento
  # se queda en estado degradado y el apply lo reporta como fallo.
  depends_on = [aws_eks_node_group.this]
}

resource "aws_eks_addon" "ebs_csi" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "aws-ebs-csi-driver"
  service_account_role_arn    = aws_iam_role.ebs_csi.arn
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = var.tags

  depends_on = [aws_eks_node_group.this]
}

# Aquí NO se crea la StorageClass de gp3, y la ausencia es deliberada.
#
# La que trae EKS por defecto usa gp2, más caro y más lento que gp3 sin ninguna
# ventaja, así que hay que sustituirla. Pero una StorageClass es un objeto de
# Kubernetes, no un recurso de AWS, y meterla aquí obligaría a configurar el
# proveedor de Kubernetes dentro del mismo apply que crea el cluster. Eso acopla
# dos proveedores en un ciclo —el segundo necesita credenciales que produce el
# primero— y es una fuente conocida de apply que funcionan y destroy que se
# quedan colgados.
#
# La frontera de este repositorio es clara: Terraform crea AWS, Argo CD crea
# Kubernetes. La StorageClass la despliega Argo con el resto de la plataforma.
