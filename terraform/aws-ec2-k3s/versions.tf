terraform {
  required_version = "~> 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # Backend parcial: aquí no se puede interpolar nada, porque Terraform lee esta
  # configuración antes de existir variables que resolver. El nombre del bucket
  # lo imprime terraform/bootstrap y se pasa al inicializar:
  #
  #   terraform init -backend-config="bucket=event-lab-tfstate-<cuenta>"
  #
  # Así el número de cuenta no acaba escrito en un repositorio público.
  #
  # use_lockfile pone el bloqueo en el propio S3. Desde Terraform 1.10 ya no hace
  # falta la tabla de DynamoDB que pedían todas las guías antiguas: un recurso
  # menos que crear, mantener y pagar.
  backend "s3" {
    key          = "aws-ec2-k3s/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "event-lab"
      ManagedBy = "terraform"
      Component = "demo-publico"
    }
  }
}
