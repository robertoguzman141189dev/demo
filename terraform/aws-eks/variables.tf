variable "region" {
  description = "Región de AWS."
  type        = string
  default     = "us-east-1"
}

variable "kubernetes_version" {
  description = "Versión del control plane. Ver el módulo para por qué 1.36."
  type        = string
  default     = "1.36"
}

variable "node_instance_types" {
  description = "Tipos de instancia de los nodos, todos ARM."
  type        = list(string)
  default     = ["t4g.medium", "t4g.large"]
}

variable "node_desired_size" {
  description = "Nodos iniciales. Dos, para que el reparto y el PDB signifiquen algo."
  type        = number
  default     = 2
}

variable "node_min_size" {
  description = "Mínimo de nodos."
  type        = number
  default     = 2
}

variable "node_max_size" {
  description = "Máximo de nodos: tope de capacidad y de gasto a la vez."
  type        = number
  default     = 3
}

variable "enable_control_plane_logs" {
  description = "Logs del control plane en CloudWatch. Apagado: se cobran por ingesta."
  type        = bool
  default     = false
}
