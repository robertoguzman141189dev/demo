variable "name" {
  description = "Nombre del cluster."
  type        = string
}

variable "kubernetes_version" {
  description = <<-EOT
    Versión de Kubernetes del control plane.

    1.36 consultado contra la API el 2026-09-08: es la más nueva en SOPORTE
    ESTÁNDAR, con margen hasta agosto de 2027.

    Que esté en soporte estándar no es un detalle burocrático: cuando una versión
    entra en soporte extendido, AWS factura el control plane a una tarifa más
    alta. Un cluster que se queda atrás no solo acumula deuda técnica, sino que
    encarece la factura sin que nadie toque nada.
  EOT
  type        = string
  default     = "1.36"
}

variable "subnet_ids" {
  description = "Subredes donde vive el cluster. EKS exige al menos dos zonas distintas."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "EKS necesita subredes en al menos dos zonas: con una sola se niega a crearse."
  }
}

variable "node_instance_types" {
  description = <<-EOT
    Tipos de instancia del grupo de nodos.

    Varios y no uno: si el primero no tiene capacidad en la zona, EKS prueba el
    siguiente en vez de dejar el grupo a medias. Todos ARM, para que coincidan
    con las imágenes que ya construimos.
  EOT
  type        = list(string)
  default     = ["t4g.medium", "t4g.large"]
}

variable "node_desired_size" {
  description = <<-EOT
    Nodos iniciales.

    Dos, no uno, y ese es medio motivo de que este cluster exista: con un nodo,
    topologySpreadConstraints no puede satisfacerse y el PodDisruptionBudget no
    tiene nada que proteger porque no hay drenaje posible. Con dos, las dos cosas
    se pueden demostrar.
  EOT
  type        = number
  default     = 2
}

variable "node_min_size" {
  description = "Mínimo de nodos del grupo."
  type        = number
  default     = 2
}

variable "node_max_size" {
  description = "Máximo de nodos. Es un tope de gasto tanto como de capacidad."
  type        = number
  default     = 3
}

variable "node_disk_size_gb" {
  description = "Disco de cada nodo."
  type        = number
  default     = 20
}

variable "enable_control_plane_logs" {
  description = <<-EOT
    Manda los logs del control plane a CloudWatch.

    Apagado por defecto. Son utilísimos para depurar problemas de autorización o
    de admisión, pero se cobran por ingesta y por almacenamiento, y un cluster de
    laboratorio que se levanta por horas no los necesita. Se encienden el día que
    haya algo que investigar.
  EOT
  type        = bool
  default     = false
}

variable "tags" {
  description = "Etiquetas adicionales."
  type        = map(string)
  default     = {}
}
