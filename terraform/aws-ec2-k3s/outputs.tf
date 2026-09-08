output "public_ip" {
  description = "IP fija del demo. Es la que apunta el DNS."
  value       = aws_eip.node.public_ip
}

output "instance_id" {
  description = "Identificador de la instancia, para abrir sesión con SSM."
  value       = aws_instance.node.id
}

output "ssm_session_command" {
  description = "Cómo entrar al nodo sin SSH ni claves."
  value       = "aws ssm start-session --target ${aws_instance.node.id} --region ${var.region}"
}

output "monthly_cost_estimate_usd" {
  description = <<-EOT
    Estimación del coste fijo mensual, con los precios consultados el 2026-09-08
    en us-east-1. No lo calcula AWS: está escrito a mano y hay que revisarlo si
    cambia el tipo de instancia o el tamaño del disco.
  EOT
  value = {
    instancia = "24.53  (t4g.medium, 0.0336 USD/h x 730)"
    disco     = "1.60   (gp3, 0.08 USD/GB-mes x 20 GB)"
    ip_fija   = "3.65   (IPv4 publica, ~0.005 USD/h)"
    total     = "~30 USD/mes frente a una alerta de 50"
  }
}
