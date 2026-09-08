# El huevo y la gallina del estado remoto.
#
# Todo lo demás en terraform/ guarda su estado en un bucket de S3. Este módulo
# es quien crea ese bucket, así que no puede usarlo: su propio estado se queda
# en local, en terraform.tfstate, y .gitignore lo mantiene fuera del
# repositorio.
#
# Es una excepción consciente y acotada. Este módulo crea tres recursos que
# nunca cambian; si su estado local se perdiera, se recuperan con dos
# `terraform import` y no se pierde nada. El estado que de verdad importa —el de
# la red, el cluster y el demo público— sí vive remoto y bloqueado.
#
# Se aplica UNA vez, a mano:
#
#   cd terraform/bootstrap && terraform init && terraform apply
#
# Después, el nombre que imprime se copia al bloque backend de los demás módulos.

data "aws_caller_identity" "current" {}

locals {
  # Los nombres de bucket son únicos en todo AWS, no solo en tu cuenta, así que
  # "event-lab-tfstate" a secas fallaría. El identificador de cuenta lo hace
  # único sin inventarse sufijos aleatorios que luego nadie recuerda.
  #
  # Se resuelve en tiempo de apply y no se escribe en el repositorio: el número
  # de cuenta no es un secreto, pero tampoco hace falta publicarlo.
  state_bucket = "event-lab-tfstate-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "state" {
  bucket = local.state_bucket

  lifecycle {
    # Borrar este bucket deja huérfana toda la infraestructura: los recursos
    # siguen vivos y facturando, y Terraform ya no sabe que existen. Esto hace
    # que un `terraform destroy` distraído falle en vez de obedecer.
    prevent_destroy = true
  }
}

# Sin versionado, un apply que corrompa el estado no tiene vuelta atrás. Con él,
# se recupera la versión anterior del objeto y se sigue.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# El estado contiene secretos en claro: contraseñas generadas, claves, cadenas
# de conexión. Terraform no los cifra dentro del archivo, así que el cifrado del
# bucket no es opcional aquí.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    # Reduce las llamadas a KMS reutilizando la clave a nivel de bucket. Con
    # AES256 gestionado por S3 es irrelevante, pero se deja explícito por si
    # algún día se pasa a una clave propia de KMS.
    bucket_key_enabled = true
  }
}

# Por el mismo motivo que el cifrado: un bucket de estado expuesto es la cuenta
# entera expuesta. Las cuatro banderas, no dos.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# El versionado guarda una copia por cada apply. Sin esta regla, el bucket crece
# para siempre guardando revisiones que nadie va a consultar.
resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  # Depende del versionado a propósito: aplicar una regla sobre versiones no
  # actuales en un bucket sin versionado es un error de configuración silencioso.
  depends_on = [aws_s3_bucket_versioning.state]

  rule {
    id     = "expirar-versiones-antiguas"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }

    # Las subidas multiparte que se quedan a medias no se ven en la consola y se
    # cobran igual. Es la fuga de dinero más tonta que tiene S3.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}
