# Versiones fijadas con ~>, no libres.
#
# Terraform no reevalúa las restricciones si ya hay un .terraform.lock.hcl, pero
# el lockfile no está en el repositorio (lo ignora .gitignore, junto con el
# resto de artefactos de terraform). Sin estas cotas, dos máquinas distintas
# podrían resolver versiones distintas del proveedor y producir planes
# diferentes con el mismo código.
terraform {
  required_version = "~> 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region

  # Etiquetas en todo lo que cree este módulo. No es burocracia: es lo que
  # permite abrir Cost Explorer y responder "cuánto me está costando event-lab"
  # sin ir recurso por recurso. Con un presupuesto de 50 USD, saber de dónde
  # sale cada dólar importa.
  default_tags {
    tags = {
      Project   = "event-lab"
      ManagedBy = "terraform"
      Component = "bootstrap"
    }
  }
}
