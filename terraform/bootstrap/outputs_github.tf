output "ecr_registry" {
  description = <<-EOT
    URL del registro de ECR.

    Es lo que el pipeline escribe en k8s/overlays/prod/job-forge/values.yaml en su
    primera ejecución. No se guarda en el repositorio porque contiene el número de
    cuenta, y este repositorio es público.
  EOT
  value       = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "github_push_role_arn" {
  description = <<-EOT
    Rol que asume el pipeline para publicar en ECR.

    Va como variable del repositorio en GitHub —Settings, Variables, no Secrets—,
    porque un ARN no es un secreto: sin el token OIDC del repositorio correcto no
    sirve de nada.

      gh variable set AWS_ROLE_ARN --body "<este valor>"
  EOT
  value       = aws_iam_role.github_push.arn
}

output "github_plan_role_arn" {
  description = "Rol de solo lectura que asumen los pull request para el plan."
  value       = aws_iam_role.github_plan.arn
}
