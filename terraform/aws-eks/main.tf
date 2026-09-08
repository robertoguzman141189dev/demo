# El laboratorio: EKS con dos nodos.
#
# Este cluster NO está pensado para vivir encendido. Cuesta unos 0,10 USD/hora
# solo de control plane, más los nodos. El procedimiento normal es levantarlo,
# trabajar y destruirlo el mismo día:
#
#   terraform apply    ... unos 15 minutos
#   ... operar ...
#   terraform destroy  ... y la factura para
#
# Los repositorios de imágenes NO están aquí sino en bootstrap, precisamente para
# que ese destroy no se lleve las imágenes por delante.

data "aws_availability_zones" "available" {
  state = "available"
}

module "network" {
  source = "../modules/network"

  name       = "event-lab-lab"
  cidr_block = "10.20.0.0/16"
  # Dos zonas: EKS se niega a crear el control plane con una sola.
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 2)

  # Sin subredes privadas, y por tanto sin NAT gateway.
  #
  # Es una concesión consciente al presupuesto: los nodos quedan en subredes
  # públicas con IP pública. En producción irían en privadas detrás de un NAT,
  # pero eso son 33 USD/mes que en un cluster que se levanta por horas no se
  # justifican. Lo que protege a los nodos aquí son los grupos de seguridad que
  # gestiona el propio EKS, no la topología.
  enable_private_subnets = false
}

module "eks" {
  source = "../modules/eks"

  name               = "event-lab"
  kubernetes_version = var.kubernetes_version
  subnet_ids         = module.network.public_subnet_ids

  node_instance_types = var.node_instance_types
  node_desired_size   = var.node_desired_size
  node_min_size       = var.node_min_size
  node_max_size       = var.node_max_size

  enable_control_plane_logs = var.enable_control_plane_logs
}
