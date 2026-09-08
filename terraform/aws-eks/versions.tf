terraform {
  required_version = "~> 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Backend parcial, igual que en el demo público: el bucket se pasa al
  # inicializar para no escribir el número de cuenta en un repositorio público.
  #
  #   terraform init -backend-config="bucket=event-lab-tfstate-<cuenta>"
  #
  # La clave es distinta a la del demo: dos estados separados, para que destruir
  # el laboratorio no pueda tocar la infraestructura que está encendida.
  backend "s3" {
    key          = "aws-eks/terraform.tfstate"
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
      Component = "laboratorio"
      # Marca explícita de que esto no debería estar encendido de continuo. Si
      # aparece en la factura a fin de mes, es que alguien olvidó el destroy.
      Lifecycle = "efimero"
    }
  }
}
