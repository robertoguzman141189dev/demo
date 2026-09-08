# El cluster de EKS: el laboratorio.
#
# No está pensado para vivir encendido. Se levanta, se opera, se destruye. Todo
# lo que hay aquí asume eso: sin logs del control plane por defecto, sin NAT, con
# un grupo de nodos pequeño y un tope de tres.

terraform {
  required_version = "~> 1.16"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

# ------------------------------------------------ identidad del cluster -----

resource "aws_iam_role" "cluster" {
  name = "${var.name}-cluster"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

# ------------------------------------------------------------- cluster -----

resource "aws_eks_cluster" "this" {
  name     = var.name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids = var.subnet_ids
    # El endpoint público se mantiene porque el kubectl de tu portátil y Argo CD
    # tienen que llegar. Restringirlo a una lista de IP es lo correcto en
    # produccón; aquí sería una molestia constante con IP domésticas cambiantes.
    endpoint_public_access  = true
    endpoint_private_access = true
  }

  access_config {
    # API y no CONFIG_MAP. El viejo mecanismo era un ConfigMap llamado aws-auth
    # que se editaba a mano: sin control de versiones, sin validación, y un error
    # de sintaxis te dejaba fuera de tu propio cluster sin forma de volver a
    # entrar. Ahora los permisos son recursos de IAM como cualquier otro.
    authentication_mode = "API"
    # Quien aplica el Terraform queda como administrador. Sin esto, el cluster se
    # crea y nadie puede entrar.
    bootstrap_cluster_creator_admin_permissions = true
  }

  enabled_cluster_log_types = var.enable_control_plane_logs ? ["api", "audit", "authenticator"] : []

  tags = var.tags

  depends_on = [aws_iam_role_policy_attachment.cluster]
}

# ------------------------------------------------------ emisor OIDC --------
#
# Esta es la pieza sobre la que se sostiene IRSA.
#
# El cluster publica un emisor OIDC: una URL que sirve las claves públicas con
# las que verificar los tokens que reparte a los pods. Registrarlo en IAM es lo
# que hace que AWS acepte "soy el ServiceAccount X del namespace Y" como prueba
# de identidad, y por tanto lo que permite que un pod asuma un rol sin llevar
# encima ninguna credencial.

data "tls_certificate" "oidc" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]

  tags = var.tags
}

# ------------------------------------------------- identidad de los nodos ---

resource "aws_iam_role" "node" {
  name = "${var.name}-node"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    # El plugin de red necesita crear interfaces y repartir IP a los pods.
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    # Solo lectura: los nodos descargan imágenes de ECR, no las publican. Quien
    # publica es el pipeline, con su propio rol.
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
    # Igual que en el demo público: acceso al nodo por Session Manager en vez de
    # abrir SSH y repartir claves.
    "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
  ])

  role       = aws_iam_role.node.name
  policy_arn = each.value
}

resource "aws_eks_node_group" "this" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name}-default"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.subnet_ids

  # Gestionado y no autogestionado: AWS se encarga de drenar el nodo viejo y
  # esperar antes de apagarlo cuando toca actualizar. Es justo donde el
  # PodDisruptionBudget de F5 entra en juego de verdad.
  instance_types = var.node_instance_types
  ami_type       = "AL2023_ARM_64_STANDARD"
  capacity_type  = "ON_DEMAND"
  disk_size      = var.node_disk_size_gb

  scaling_config {
    desired_size = var.node_desired_size
    min_size     = var.node_min_size
    max_size     = var.node_max_size
  }

  update_config {
    max_unavailable = 1
  }

  tags = var.tags

  lifecycle {
    # El tamaño deseado lo puede mover un autoescalador. Sin esto, cada apply lo
    # devolvería al valor del código y pelearía contra quien escala.
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}
