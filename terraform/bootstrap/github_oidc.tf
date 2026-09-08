# La confianza entre GitHub Actions y AWS, sin una sola llave guardada.
#
# El problema: el pipeline necesita publicar imágenes en ECR. Lo perezoso es
# crear un usuario IAM, generarle unas access keys y pegarlas como secreto del
# repositorio. Eso es una credencial permanente, que nadie rota, en un sistema
# que no controlas del todo.
#
# Cómo funciona esto en su lugar. GitHub firma, en cada ejecución, un token que
# dice de qué repositorio viene, de qué rama y de qué evento. AWS acepta ese token
# como prueba de identidad porque conoce las claves públicas de GitHub, y a cambio
# entrega credenciales temporales que caducan al terminar el job. No hay nada
# guardado en ningún sitio.
#
# Vive en bootstrap porque no depende de ningún cluster: el pipeline publica
# imágenes exista o no exista EKS en ese momento.

variable "github_repository" {
  description = "Repositorio autorizado a asumir los roles, en formato owner/repo."
  type        = string
  default     = "robertoguzman141189dev/demo"

  validation {
    condition     = can(regex("^[^/]+/[^/]+$", var.github_repository))
    error_message = "github_repository tiene que ser owner/repo."
  }
}

data "tls_certificate" "github" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # AWS dejó de validar esta huella para GitHub —verifica contra la CA— pero el
  # recurso sigue exigiendo el campo. Se calcula en vez de copiarse a mano para
  # que no quede un valor obsoleto pegado en el código.
  thumbprint_list = [data.tls_certificate.github.certificates[0].sha1_fingerprint]
}

# ------------------------------------------------ rol de publicación --------

data "aws_iam_policy_document" "github_push_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # ESTA es la condición que importa, y donde se cometen los errores caros.
    #
    # El sub codifica repositorio, tipo de referencia y rama. Fijado así, solo las
    # ejecuciones sobre main de ESTE repositorio pueden asumir el rol.
    #
    # Dejarlo con un comodín amplio —"repo:mi-org/*" o, peor, "repo:*"— permite
    # que el workflow de cualquiera asuma tu rol. No da ningún error: simplemente
    # funciona de más, y es un fallo conocido y explotado. Si algún día hay que
    # ampliarlo, se amplía con StringLike y un patrón acotado, nunca con :*.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_push" {
  name               = "event-lab-github-push"
  description        = "Publica imagenes en ECR desde main. Sin llaves permanentes."
  assume_role_policy = data.aws_iam_policy_document.github_push_assume.json
  # Una hora es de sobra para construir y publicar cuatro imágenes, y acota el
  # daño si el token se filtrara en un log.
  max_session_duration = 3600
}

data "aws_iam_policy_document" "github_push" {
  # El token de autenticación de ECR no se puede pedir sobre un repositorio
  # concreto: la acción no admite recurso. Es la única que va con "*".
  statement {
    sid       = "TokenDeRegistro"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # Todo lo demás sí se acota a los cuatro repositorios de este proyecto. No hay
  # permiso para borrar imágenes ni para crear repositorios nuevos: el pipeline
  # publica y nada más.
  statement {
    sid    = "PublicarImagenes"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [for r in aws_ecr_repository.service : r.arn]
  }
}

resource "aws_iam_role_policy" "github_push" {
  name   = "publicar-en-ecr"
  role   = aws_iam_role.github_push.id
  policy = data.aws_iam_policy_document.github_push.json
}

# ------------------------------------------------------- rol de lectura -----
# Rol aparte para el `terraform plan` de los pull request. Separado del anterior
# a propósito: un plan solo necesita LEER, y un pull request puede venir de
# cualquiera. Darle el rol de publicación sería regalar permiso de escritura a
# quien abra una rama.

data "aws_iam_policy_document" "github_plan_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # Solo pull request de este repositorio, no ramas arbitrarias.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:pull_request"]
    }
  }
}

resource "aws_iam_role" "github_plan" {
  name                 = "event-lab-github-plan"
  description          = "Solo lectura, para terraform plan en pull requests"
  assume_role_policy   = data.aws_iam_policy_document.github_plan_assume.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy_attachment" "github_plan" {
  role = aws_iam_role.github_plan.name
  # Solo lectura de toda la cuenta. Un plan necesita leer casi cualquier cosa
  # para comparar con el estado, y acotarlo recurso a recurso sería una lista
  # inmantenible que rompería el plan cada vez que se añade algo nuevo.
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# También necesita leer y escribir el bloqueo del estado, que ReadOnlyAccess no
# cubre. Sin esto el plan falla al intentar adquirir el bloqueo.
data "aws_iam_policy_document" "github_plan_state" {
  statement {
    sid       = "BloqueoDelEstado"
    effect    = "Allow"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.state.arn}/*.tflock"]
  }
}

resource "aws_iam_role_policy" "github_plan_state" {
  name   = "bloqueo-del-estado"
  role   = aws_iam_role.github_plan.id
  policy = data.aws_iam_policy_document.github_plan_state.json
}
