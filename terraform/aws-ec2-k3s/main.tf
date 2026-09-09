# El demo público: una EC2 con k3s, encendida de continuo.
#
# Aquí no hay alta disponibilidad y no se finge que la haya. Es un nodo. Si se
# cae, el demo se cae. Esa es exactamente la razón de que el demo viva aquí y no
# en EKS: mantener un control plane gestionado encendido todo el mes cuesta 73
# USD antes de desplegar nada, y este montaje cuesta unos 30. El ADR 008 lo
# argumenta con los precios medidos.

data "aws_availability_zones" "available" {
  state = "available"
}

# La AMI se resuelve en cada plan desde el parámetro público de SSM en vez de
# fijarse a un identificador. Un ami-xxxx escrito a mano queda obsoleto y sin
# parches de seguridad, y encima cambia entre regiones.
data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

module "network" {
  source = "../modules/network"

  name       = "event-lab-demo"
  cidr_block = "10.10.0.0/16"
  # Una zona basta: es un nodo. Repartir entre zonas sin nada que repartir solo
  # añade subredes vacías.
  availability_zones = [data.aws_availability_zones.available.names[0]]
  # Sin subredes privadas y por tanto sin NAT gateway. El nodo está en la subred
  # pública con su IP: son 33 USD al mes que no se pagan.
  enable_private_subnets = false
}

# ------------------------------------------------------------- seguridad ----

resource "aws_security_group" "node" {
  name        = "event-lab-demo-node"
  description = "Nodo k3s del demo publico"
  vpc_id      = module.network.vpc_id

  tags = { Name = "event-lab-demo-node" }
}

# HTTP y HTTPS abiertos al mundo: es un demo público y anónimo por diseño. El
# blindaje de ese endpoint —límites por sesión y globales— es trabajo de F7 y se
# hace en el ingress, no aquí: un grupo de seguridad no sabe contar peticiones.
resource "aws_vpc_security_group_ingress_rule" "http" {
  security_group_id = aws_security_group.node.id
  description       = "HTTP del panel publico"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.node.id
  description       = "HTTPS del panel publico"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

# La API de Kubernetes solo si se pide expresamente, y acotada. Sin admin_cidr
# no se crea la regla: el puerto 6443 abierto al mundo es entregar el cluster.
resource "aws_vpc_security_group_ingress_rule" "kube_api" {
  count = var.admin_cidr == "" ? 0 : 1

  security_group_id = aws_security_group.node.id
  description       = "API de Kubernetes, solo desde la IP del administrador"
  cidr_ipv4         = var.admin_cidr
  from_port         = 6443
  to_port           = 6443
  ip_protocol       = "tcp"
}

# No hay regla para el 22 y no es un olvido: no se abre SSH. El acceso al nodo es
# por SSM Session Manager, que sale del propio nodo hacia AWS y no necesita ni
# puerto abierto ni par de claves que custodiar y rotar.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.node.id
  description       = "Salida libre: descargar imagenes y hablar con SSM"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ----------------------------------------------------------- identidad ------

resource "aws_iam_role" "node" {
  name = "event-lab-demo-node"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Lo mínimo para que Session Manager funcione. Es una política gestionada por
# AWS: sustituye a abrir el 22 y a repartir claves privadas.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Solo lectura sobre ECR: el nodo descarga imágenes, no las publica. Quien publica
# es el pipeline, con su propio rol y su propia condición de confianza.
#
# Faltaba, y el síntoma fue un ErrImagePull con "no basic auth credentials" que no
# menciona IAM por ningún lado.
resource "aws_iam_role_policy_attachment" "ecr_read" {
  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_iam_instance_profile" "node" {
  name = "event-lab-demo-node"
  role = aws_iam_role.node.name
}

# --------------------------------------------------------------- nodo -------

resource "aws_instance" "node" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.instance_type
  subnet_id              = module.network.public_subnet_ids[0]
  vpc_security_group_ids = [aws_security_group.node.id]
  iam_instance_profile   = aws_iam_instance_profile.node.name

  user_data                   = local.user_data
  user_data_replace_on_change = true

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
    encrypted   = true
  }

  # IMDSv2 obligatorio. Con IMDSv1 basta con que algo dentro del nodo haga una
  # petición HTTP a una URL controlada por el atacante —un SSRF— para llevarse
  # las credenciales del rol de la instancia. Exigir token cierra esa puerta.
  metadata_options {
    http_tokens                 = "required"
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 2
  }

  tags = { Name = "event-lab-demo" }
}

# IP estable, separada de la instancia. Sin esto, cada recreación del nodo
# cambia la dirección y hay que retocar el DNS del demo.
resource "aws_eip" "node" {
  instance = aws_instance.node.id
  domain   = "vpc"
  tags     = { Name = "event-lab-demo" }

  depends_on = [module.network]
}
