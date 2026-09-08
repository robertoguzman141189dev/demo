variable "name" {
  description = "Prefijo de los nombres. Distingue la red del demo público de la del laboratorio."
  type        = string
}

variable "cidr_block" {
  description = "Rango de la VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = <<-EOT
    Zonas donde repartir las subredes.

    Una sola zona basta para el demo público, que es un nodo. EKS exige al menos
    dos: el control plane gestionado vive repartido y se niega a crearse si solo
    le das una.
  EOT
  type        = list(string)
}

variable "enable_private_subnets" {
  description = <<-EOT
    Crea subredes privadas además de las públicas.

    Apagado por defecto. Una subred privada sin salida a internet no sirve de
    nada, y darle salida significa un NAT gateway, que cuesta 0,045 USD/hora
    —unos 33 al mes— más el tráfico procesado. Con un presupuesto de 50, esa
    decisión se toma a conciencia, no por costumbre.
  EOT
  type        = bool
  default     = false
}

variable "enable_nat_gateway" {
  description = <<-EOT
    Da salida a internet a las subredes privadas.

    Solo tiene efecto con enable_private_subnets. Uno solo, no uno por zona: la
    versión resistente a la caída de una zona multiplica el coste por el número
    de zonas, y este cluster se levanta por horas.
  EOT
  type        = bool
  default     = false
}

variable "tags" {
  description = "Etiquetas adicionales para los recursos de red."
  type        = map(string)
  default     = {}
}
