variable "region" {
  description = "Región de AWS donde vive el bucket del estado."
  type        = string
  default     = "us-east-1"
}

variable "budget_limit_usd" {
  description = "Tope mensual en dólares a partir del cual avisa la alerta de gasto."
  type        = number
  default     = 50
}

variable "alert_email" {
  description = <<-EOT
    Correo que recibe las alertas de gasto.

    Sin valor por defecto a propósito: un apply sin este dato debe fallar, no
    crear un presupuesto que no avisa a nadie. Y no se escribe en el repositorio,
    que es público; se pasa con -var o en un .tfvars, que .gitignore ya excluye.

    AWS manda un correo de confirmación la primera vez. Hasta que se acepte, la
    alerta existe pero no llega a ningún sitio.
  EOT
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alert_email))
    error_message = "alert_email tiene que ser una dirección de correo válida."
  }
}

variable "noncurrent_version_retention_days" {
  description = <<-EOT
    Días que se conservan las versiones antiguas del estado antes de borrarse.

    El versionado del bucket es la red de seguridad de la que se tira cuando un
    apply corrompe el estado: se recupera la versión anterior. Treinta días es
    tiempo de sobra para darse cuenta, y evita pagar por guardar para siempre
    cada revisión de un archivo que cambia en cada apply.
  EOT
  type        = number
  default     = 30
}
