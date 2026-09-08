# Los repositorios de imágenes.
#
# Viven aquí y no en terraform/aws-eks/ por una razón que importa: el cluster de
# EKS es efímero y se destruye al terminar cada sesión. Si los repositorios
# colgaran de esa composición, cada `terraform destroy` se llevaría por delante
# todas las imágenes publicadas, incluida la que está corriendo ahora mismo en el
# demo público.
#
# La regla es esa: en bootstrap va lo que sobrevive a todo. Estado, alerta de
# gasto y artefactos.

locals {
  services = ["api", "worker", "audit", "ui"]
}

resource "aws_ecr_repository" "service" {
  for_each = toset(local.services)

  name = "event-lab/job-forge-${each.value}"

  # Un tag escrito no se puede reescribir. Es lo que convierte el SHA del commit
  # en una referencia de verdad: si job-forge-api:abc123 siempre es el mismo
  # binario, del contenedor en producción se llega al código exacto. Con tags
  # mutables, dos nodos pueden estar ejecutando cosas distintas bajo el mismo
  # nombre, y es de los fallos más difíciles de diagnosticar que existen.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  # force_delete queda en false a propósito: borrar un repositorio con imágenes
  # dentro debe costar un paso consciente.
  force_delete = false
}

# Sin esto el repositorio crece para siempre. Las imágenes sin tag son capas
# huérfanas de builds sobrescritos; las antiguas, versiones que ya nadie despliega.
resource "aws_ecr_lifecycle_policy" "service" {
  for_each = aws_ecr_repository.service

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Borrar imagenes sin tag al dia siguiente"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Conservar solo las 20 imagenes mas recientes"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 20
        }
        action = { type = "expire" }
      },
    ]
  })
}
