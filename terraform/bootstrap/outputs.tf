output "state_bucket" {
  description = <<-EOT
    Nombre del bucket del estado.

    Este valor va copiado en el bloque `backend "s3"` de los demás módulos. No se
    puede interpolar allí: la configuración del backend se lee antes de que exista
    nada que interpolar, así que Terraform no admite variables ahí. Es la razón de
    que este output exista en vez de resolverse solo.
  EOT
  value       = aws_s3_bucket.state.id
}

output "region" {
  description = "Región del bucket, que también hay que repetir en cada backend."
  value       = var.region
}
