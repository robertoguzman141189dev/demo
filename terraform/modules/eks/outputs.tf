output "cluster_name" {
  description = "Nombre del cluster."
  value       = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  description = "Endpoint de la API de Kubernetes."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_version" {
  description = "Versión de Kubernetes en ejecución."
  value       = aws_eks_cluster.this.version
}

output "oidc_provider_arn" {
  description = <<-EOT
    ARN del proveedor OIDC del cluster.

    Es lo que se necesita para crear roles asumibles por un ServiceAccount: la
    materia prima de IRSA para las aplicaciones.
  EOT
  value       = aws_iam_openid_connect_provider.this.arn
}

output "oidc_issuer_host" {
  description = <<-EOT
    Emisor OIDC sin el esquema.

    Se expone ya recortado porque es la forma en que lo quieren las condiciones de
    confianza de IAM, y dejarlo con el https:// es el error que hace que la
    condición nunca case y el pod reciba un AccessDenied sin explicación.
  EOT
  value       = local.oidc_issuer_host
}

output "node_role_arn" {
  description = "Rol de los nodos, por si hay que ampliarle permisos."
  value       = aws_iam_role.node.arn
}

output "kubeconfig_command" {
  description = "Cómo apuntar kubectl a este cluster."
  value       = "aws eks update-kubeconfig --name ${aws_eks_cluster.this.name}"
}
