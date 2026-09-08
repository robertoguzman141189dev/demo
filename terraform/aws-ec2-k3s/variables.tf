variable "region" {
  description = "Región de AWS."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = <<-EOT
    Tipo de instancia del nodo.

    t4g.medium: 2 vCPU y 4 GiB, familia Graviton (ARM). Se eligió midiendo, no a
    ojo: en kind el despliegue completo pedía unos 2,5 GiB solo en requests, más
    lo que consume el propio Kubernetes. En 4 GiB entra con el overlay de
    producción recortado a un worker; en 2 GiB no entra.

    ARM y no x86 porque cuesta la mitad para la misma memoria, y todas las
    imágenes de este repositorio se construyen multiplataforma.
  EOT
  type        = string
  default     = "t4g.medium"
}

variable "root_volume_size_gb" {
  description = "Disco del nodo. Aloja el sistema, las imágenes y los datos de los brokers."
  type        = number
  default     = 20
}

variable "k3s_version" {
  description = <<-EOT
    Versión de k3s a instalar, fijada.

    Sin fijarla, el script de instalación coge la última estable, y dos apply
    separados por meses producen clusters distintos a partir del mismo código.
  EOT
  type        = string
  default     = "v1.33.4+k3s1"
}

variable "admin_cidr" {
  description = <<-EOT
    Desde dónde se permite alcanzar la API de Kubernetes (6443).

    Vacío por defecto, que significa "desde ningún sitio". El acceso normal al
    nodo es por SSM Session Manager, que no necesita ni puertos abiertos ni
    claves; esto solo se rellena si quieres apuntar un kubectl local, y entonces
    debe ser tu IP con /32, nunca 0.0.0.0/0.
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.admin_cidr == "" || can(cidrhost(var.admin_cidr, 0))
    error_message = "admin_cidr tiene que estar vacío o ser un CIDR válido."
  }
}
