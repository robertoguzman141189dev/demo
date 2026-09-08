# Red compartida por las dos composiciones.
#
# El demo público la usa en su forma mínima: una zona, una subred pública, sin
# NAT. El laboratorio de EKS la usa con dos zonas, porque el control plane
# gestionado se niega a crearse con una sola.

terraform {
  required_version = "~> 1.16"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

locals {
  # Subredes /24 talladas del /16 de la VPC. Las públicas ocupan los primeros
  # índices y las privadas empiezan en el 100, para que al mirar una IP se sepa
  # de un vistazo de qué lado está.
  public_subnets  = { for i, az in var.availability_zones : az => cidrsubnet(var.cidr_block, 8, i) }
  private_subnets = var.enable_private_subnets ? { for i, az in var.availability_zones : az => cidrsubnet(var.cidr_block, 8, i + 100) } : {}
}

resource "aws_vpc" "this" {
  cidr_block = var.cidr_block

  # Los dos hacen falta para que la resolución de nombres interna funcione. Sin
  # enable_dns_hostnames, el endpoint privado de EKS no resuelve y el síntoma es
  # un kubectl que se queda colgado sin explicar por qué.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, { Name = var.name })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = var.name })
}

resource "aws_subnet" "public" {
  for_each = local.public_subnets

  vpc_id                  = aws_vpc.this.id
  cidr_block              = each.value
  availability_zone       = each.key
  map_public_ip_on_launch = true

  tags = merge(var.tags, {
    Name = "${var.name}-public-${each.key}"
    # La etiqueta que usa el controlador de balanceadores de EKS para saber
    # dónde puede colocar un Service de tipo LoadBalancer expuesto a internet.
    # Sin ella el Service se queda en <pending> para siempre y el motivo no
    # aparece en ningún log evidente.
    "kubernetes.io/role/elb" = "1"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# ------------------------------------------------------- privadas y NAT ------
# Todo lo de aquí abajo solo existe si se pide expresamente, porque el NAT
# gateway es la línea de factura que más sorprende: cuesta casi lo mismo que el
# control plane de EKS y no aparece hasta que llega el recibo.

resource "aws_subnet" "private" {
  for_each = local.private_subnets

  vpc_id            = aws_vpc.this.id
  cidr_block        = each.value
  availability_zone = each.key

  tags = merge(var.tags, {
    Name                              = "${var.name}-private-${each.key}"
    "kubernetes.io/role/internal-elb" = "1"
  })
}

resource "aws_eip" "nat" {
  count  = var.enable_private_subnets && var.enable_nat_gateway ? 1 : 0
  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name}-nat" })
}

resource "aws_nat_gateway" "this" {
  count = var.enable_private_subnets && var.enable_nat_gateway ? 1 : 0

  allocation_id = aws_eip.nat[0].id
  # Un NAT vive en una subred PÚBLICA y da salida a las privadas. Colocarlo en
  # una privada es el error clásico: se crea sin quejarse y no encamina nada.
  subnet_id = values(aws_subnet.public)[0].id

  depends_on = [aws_internet_gateway.this]
  tags       = merge(var.tags, { Name = var.name })
}

resource "aws_route_table" "private" {
  count = var.enable_private_subnets ? 1 : 0

  vpc_id = aws_vpc.this.id

  # Sin NAT, esta tabla no tiene ruta por defecto: las subredes privadas quedan
  # sin salida a internet. Es intencionado y es la opción barata.
  dynamic "route" {
    for_each = var.enable_nat_gateway ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = aws_nat_gateway.this[0].id
    }
  }

  tags = merge(var.tags, { Name = "${var.name}-private" })
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private[0].id
}
