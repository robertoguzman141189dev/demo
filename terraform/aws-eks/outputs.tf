output "cluster_name" {
  description = "Nombre del cluster."
  value       = module.eks.cluster_name
}

output "kubeconfig_command" {
  description = "Cómo apuntar kubectl a este cluster."
  value       = module.eks.kubeconfig_command
}

output "oidc_provider_arn" {
  description = "Proveedor OIDC del cluster: la materia prima de IRSA."
  value       = module.eks.oidc_provider_arn
}

output "oidc_issuer_host" {
  description = "Emisor OIDC sin esquema, tal como lo quieren las condiciones de IAM."
  value       = module.eks.oidc_issuer_host
}

output "hourly_cost_estimate_usd" {
  description = <<-EOT
    Lo que cuesta este cluster por hora encendido. Precios de us-east-1
    consultados el 2026-09-08 contra la API de AWS.

    Está aquí a propósito: que aparezca en cada `terraform output` recuerda que
    esto no se deja puesto.
  EOT
  value = {
    control_plane = "0.100 USD/h"
    dos_nodos     = "0.067 USD/h  (2 x t4g.medium a 0.0336)"
    total         = "~0.17 USD/h  ->  0.70 por una sesion de 4 h, 122 al mes si se olvida"
    recordatorio  = "terraform destroy al terminar"
  }
}
